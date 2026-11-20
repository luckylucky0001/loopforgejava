package io.github.loopforge4j.core.runtime.git;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.math.BigInteger;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.spec.ECGenParameterSpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/** The in-process SSH agent and the private key formats it reads. */
public class SshAgentTest {

    // ------------------------------------------------------------------ test keys

    static KeyPair generate(String algorithm, Object spec) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance(algorithm);
        if (spec instanceof Integer bits) {
            generator.initialize(bits);
        } else if (spec != null) {
            generator.initialize((java.security.spec.AlgorithmParameterSpec) spec);
        }
        return generator.generateKeyPair();
    }

    static String pem(String label, byte[] der) {
        return "-----BEGIN " + label + "-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(der) + "\n-----END " + label + "-----\n";
    }

    /** OpenSSH private key text (unencrypted unless {@code cipher} is given). */
    static String openSsh(KeyPair pair, String cipher) {
        SshKeys.Wire priv = new SshKeys.Wire().uint32(0x1234).uint32(0x1234);
        byte[] pub;
        if (pair.getPublic().getAlgorithm().equals("Ed25519")
                || pair.getPublic().getAlgorithm().equals("EdDSA")) {
            byte[] raw = ed25519Raw(pair.getPublic());
            byte[] pkcs8 = pair.getPrivate().getEncoded();
            byte[] seed = Arrays.copyOfRange(pkcs8, pkcs8.length - 32, pkcs8.length);
            pub = new SshKeys.Wire().string("ssh-ed25519").string(raw).bytes();
            byte[] secret = new byte[64];
            System.arraycopy(seed, 0, secret, 0, 32);
            System.arraycopy(raw, 0, secret, 32, 32);
            priv.string("ssh-ed25519").string(raw).string(secret);
        } else if (pair.getPrivate() instanceof RSAPrivateCrtKey k) {
            pub = new SshKeys.Wire()
                    .string("ssh-rsa")
                    .mpint(k.getPublicExponent())
                    .mpint(k.getModulus())
                    .bytes();
            priv.string("ssh-rsa")
                    .mpint(k.getModulus())
                    .mpint(k.getPublicExponent())
                    .mpint(k.getPrivateExponent())
                    .mpint(k.getCrtCoefficient())
                    .mpint(k.getPrimeP())
                    .mpint(k.getPrimeQ());
        } else {
            ECPrivateKey k = (ECPrivateKey) pair.getPrivate();
            byte[] q = point((ECPublicKey) pair.getPublic(), 32);
            pub = new SshKeys.Wire()
                    .string("ecdsa-sha2-nistp256")
                    .string("nistp256")
                    .string(q)
                    .bytes();
            priv.string("ecdsa-sha2-nistp256").string("nistp256").string(q).mpint(k.getS());
        }
        priv.string("test@example");
        byte[] section = priv.bytes();
        int pad = (8 - section.length % 8) % 8;
        SshKeys.Wire padded = new SshKeys.Wire();
        for (byte b : section) {
            padded.raw(b);
        }
        for (int i = 1; i <= pad; i++) {
            padded.raw(i);
        }
        SshKeys.Wire file = new SshKeys.Wire();
        for (byte b : "openssh-key-v1\0".getBytes(StandardCharsets.US_ASCII)) {
            file.raw(b);
        }
        file.string(cipher == null ? "none" : cipher)
                .string(cipher == null ? "none" : "bcrypt")
                .string(new byte[0])
                .uint32(1)
                .string(pub)
                .string(padded.bytes());
        return pem("OPENSSH PRIVATE KEY", file.bytes());
    }

    static byte[] ed25519Raw(PublicKey key) {
        byte[] x509 = key.getEncoded();
        return Arrays.copyOfRange(x509, x509.length - 32, x509.length);
    }

    static byte[] point(ECPublicKey key, int size) {
        byte[] out = new byte[1 + 2 * size];
        out[0] = 4;
        byte[] x = unsigned(key.getW().getAffineX(), size);
        byte[] y = unsigned(key.getW().getAffineY(), size);
        System.arraycopy(x, 0, out, 1, size);
        System.arraycopy(y, 0, out, 1 + size, size);
        return out;
    }

    static byte[] unsigned(BigInteger value, int size) {
        byte[] bytes = value.toByteArray();
        byte[] out = new byte[size];
        int skip = Math.max(0, bytes.length - size);
        System.arraycopy(bytes, skip, out, size - (bytes.length - skip), bytes.length - skip);
        return out;
    }

    /** A fresh unencrypted Ed25519 key in OpenSSH format (for other tests). */
    public static String openSshKey() throws Exception {
        return openSsh(generate("Ed25519", null), null);
    }

    // ------------------------------------------------------------------ the agent protocol

    static byte[] request(Path socket, byte[] message) throws IOException {
        try (SocketChannel channel = SocketChannel.open(StandardProtocolFamily.UNIX)) {
            channel.connect(UnixDomainSocketAddress.of(socket));
            ByteBuffer out = ByteBuffer.allocate(4 + message.length)
                    .putInt(message.length)
                    .put(message)
                    .flip();
            while (out.hasRemaining()) {
                channel.write(out);
            }
            ByteBuffer header = ByteBuffer.allocate(4);
            while (header.hasRemaining()) {
                channel.read(header);
            }
            ByteBuffer body = ByteBuffer.allocate(header.flip().getInt());
            while (body.hasRemaining()) {
                channel.read(body);
            }
            return body.array();
        }
    }

    static byte[] sign(Path socket, byte[] blob, byte[] data, int flags) throws IOException {
        byte[] reply = request(
                socket,
                new SshKeys.Wire()
                        .raw(13)
                        .string(blob)
                        .string(data)
                        .uint32(flags)
                        .bytes());
        if (reply[0] != 14) {
            return null;
        }
        return new SshKeys.Reader(Arrays.copyOfRange(reply, 1, reply.length)).bytes();
    }

    static Path socketDir() throws IOException {
        // short: a Unix socket path has at most 107 bytes
        return Files.createTempDirectory(Path.of("/tmp"), "lfa");
    }

    @Test
    void listsAndSignsWithEveryKeyType() throws Exception {
        KeyPair ed = generate("Ed25519", null);
        KeyPair rsa = generate("RSA", 2048);
        KeyPair ec = generate("EC", new ECGenParameterSpec("secp256r1"));
        SshKeys.Identity edId = SshKeys.parse(openSsh(ed, null));
        SshKeys.Identity rsaId = SshKeys.parse(openSsh(rsa, null));
        SshKeys.Identity ecId = SshKeys.parse(openSsh(ec, null));
        Path socket = socketDir().resolve("a");
        byte[] data = "session-id and userauth request".getBytes(StandardCharsets.UTF_8);
        try (SshAgent agent = SshAgent.start(socket, List.of(edId, rsaId, ecId))) {
            assertThat(Files.getPosixFilePermissions(socket))
                    .containsExactlyInAnyOrder(
                            java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                            java.nio.file.attribute.PosixFilePermission.OWNER_WRITE);
            byte[] answer = request(socket, new byte[] {11});
            assertThat(answer[0]).isEqualTo((byte) 12);
            SshKeys.Reader in = new SshKeys.Reader(Arrays.copyOfRange(answer, 1, answer.length));
            assertThat(in.uint32()).isEqualTo(3);
            assertThat(in.bytes()).isEqualTo(edId.publicBlob());
            in.text();
            assertThat(in.bytes()).isEqualTo(rsaId.publicBlob());

            SshKeys.Reader edSig = new SshKeys.Reader(sign(socket, edId.publicBlob(), data, 0));
            assertThat(edSig.text()).isEqualTo("ssh-ed25519");
            assertThat(verify("Ed25519", ed.getPublic(), data, edSig.bytes())).isTrue();

            for (int[] flag : new int[][] {{0}, {2}, {4}}) {
                SshKeys.Reader rsaSig = new SshKeys.Reader(sign(socket, rsaId.publicBlob(), data, flag[0]));
                String algorithm = rsaSig.text();
                String jca = switch (flag[0]) {
                    case 2 -> "SHA256withRSA";
                    case 4 -> "SHA512withRSA";
                    default -> "SHA1withRSA";
                };
                assertThat(algorithm)
                        .isEqualTo(flag[0] == 2 ? "rsa-sha2-256" : flag[0] == 4 ? "rsa-sha2-512" : "ssh-rsa");
                assertThat(verify(jca, rsa.getPublic(), data, rsaSig.bytes())).isTrue();
            }

            SshKeys.Reader ecSig = new SshKeys.Reader(sign(socket, ecId.publicBlob(), data, 0));
            assertThat(ecSig.text()).isEqualTo("ecdsa-sha2-nistp256");
            SshKeys.Reader rs = new SshKeys.Reader(ecSig.bytes());
            assertThat(verify("SHA256withECDSA", ec.getPublic(), data, der(rs.mpint(), rs.mpint())))
                    .isTrue();

            // a key the agent does not hold, other requests and garbage are refused
            KeyPair other = generate("Ed25519", null);
            assertThat(sign(socket, SshKeys.parse(openSsh(other, null)).publicBlob(), data, 0))
                    .isNull();
            assertThat(request(socket, new byte[] {17, 0, 0, 0, 0})).containsExactly(5);
            assertThat(request(socket, new byte[] {19})).containsExactly(5);
            assertThat(request(socket, new byte[] {13, 0, 0})).containsExactly(5);
            assertThat(agent.socket()).isEqualTo(socket);
        }
        assertThat(socket).doesNotExist();
        assertThatThrownBy(() -> request(socket, new byte[] {11})).isInstanceOf(IOException.class);
    }

    static boolean verify(String algorithm, PublicKey key, byte[] data, byte[] signature) throws Exception {
        Signature verifier = Signature.getInstance(algorithm);
        verifier.initVerify(key);
        verifier.update(data);
        return verifier.verify(signature);
    }

    static byte[] der(BigInteger r, BigInteger s) {
        byte[] rb = r.toByteArray();
        byte[] sb = s.toByteArray();
        byte[] out = new byte[6 + rb.length + sb.length];
        out[0] = 0x30;
        out[1] = (byte) (4 + rb.length + sb.length);
        out[2] = 0x02;
        out[3] = (byte) rb.length;
        System.arraycopy(rb, 0, out, 4, rb.length);
        out[4 + rb.length] = 0x02;
        out[5 + rb.length] = (byte) sb.length;
        System.arraycopy(sb, 0, out, 6 + rb.length, sb.length);
        return out;
    }

    // ------------------------------------------------------------------ key formats

    @Test
    void readsPemFormatsWithTheSamePublicKey() throws Exception {
        KeyPair rsa = generate("RSA", 2048);
        KeyPair ec = generate("EC", new ECGenParameterSpec("secp256r1"));
        KeyPair ed = generate("Ed25519", null);
        String fromOpenSsh = SshKeys.parse(openSsh(rsa, null)).fingerprint();
        assertThat(SshKeys.parse(pem("PRIVATE KEY", rsa.getPrivate().getEncoded()))
                        .fingerprint())
                .isEqualTo(fromOpenSsh);
        byte[] pkcs1 = SshKeys.Der.read(rsa.getPrivate().getEncoded())
                .children()
                .get(2)
                .content();
        assertThat(SshKeys.parse(pem("RSA PRIVATE KEY", pkcs1)).fingerprint()).isEqualTo(fromOpenSsh);
        // the JDK leaves the public point out of PKCS#8 EC keys: it is derived
        assertThat(SshKeys.parse(pem("PRIVATE KEY", ec.getPrivate().getEncoded()))
                        .publicBlob())
                .isEqualTo(SshKeys.parse(openSsh(ec, null)).publicBlob());
        assertThat(SshKeys.parse(pem("PRIVATE KEY", ed.getPrivate().getEncoded()))
                        .publicBlob())
                .isEqualTo(SshKeys.parse(openSsh(ed, null)).publicBlob());

        SshKeys.Identity identity = SshKeys.parse(openSsh(ed, null));
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(identity.publicBlob());
        assertThat(identity.fingerprint())
                .isEqualTo("SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest));
        assertThat(identity.publicKeyLine())
                .isEqualTo("ssh-ed25519 " + Base64.getEncoder().encodeToString(identity.publicBlob()));
        assertThat(identity.toString()).doesNotContain("PRIVATE").contains("SHA256:");
    }

    @Test
    void refusesKeysThatCannotBeUsedUnattended() throws Exception {
        KeyPair ed = generate("Ed25519", null);
        assertThatThrownBy(() -> SshKeys.parse(openSsh(ed, "aes256-ctr")))
                .isInstanceOf(SshKeys.InvalidKeyException.class)
                .hasMessageContaining("口令");
        assertThatThrownBy(() -> SshKeys.parse(
                        "-----BEGIN RSA PRIVATE KEY-----\nProc-Type: 4,ENCRYPTED\nDEK-Info: AES-128-CBC,00\n\nAAAA\n"
                                + "-----END RSA PRIVATE KEY-----\n"))
                .hasMessageContaining("口令");
        assertThatThrownBy(() -> SshKeys.parse(pem("ENCRYPTED PRIVATE KEY", new byte[] {1})))
                .hasMessageContaining("口令");
        assertThatThrownBy(() -> SshKeys.parse("ssh-ed25519 AAAA… a public key"))
                .hasMessageContaining("格式无效");
        assertThatThrownBy(() -> SshKeys.parse(pem("OPENSSH PRIVATE KEY", "garbage".getBytes())))
                .hasMessageContaining("格式无效");
        assertThatThrownBy(() -> SshKeys.parse(" ")).hasMessageContaining("请粘贴");
        assertThatThrownBy(() -> SshKeys.parse("x".repeat(SshKeys.MAX_LENGTH + 1)))
                .hasMessageContaining("16 KiB");
        assertThatThrownBy(() -> SshKeys.parse(
                        pem("PRIVATE KEY", generate("RSA", 1024).getPrivate().getEncoded())))
                .hasMessageContaining("2048");
    }

    // ------------------------------------------------------------------ the binding of a command

    @Test
    void bindingServesKeysPerHostAndLeavesNoKeyMaterialBehind() throws Exception {
        Path workspace = socketDir();
        Path legacy = workspace.resolve(".loopforge/ssh/id_loopforge");
        Files.createDirectories(legacy.getParent());
        Files.writeString(legacy, "-----BEGIN OPENSSH PRIVATE KEY-----\nold\n-----END OPENSSH PRIVATE KEY-----\n");
        String personal = openSsh(generate("Ed25519", null), null);
        String deployment = openSsh(generate("Ed25519", null), null);
        GitCredentials credentials = new GitCredentials(
                Map.of(), deployment, null, null, Map.of("github.com", personal, "bad host!", personal), "");
        Path configFile;
        Path socket;
        try (GitEnvironment.Binding binding = GitEnvironment.bind(workspace, credentials, List.of())) {
            String command = binding.environment().get("GIT_SSH_COMMAND");
            assertThat(command).startsWith("ssh -F '");
            assertThat(binding.environment()).containsEntry("GIT_SSH_VARIANT", "ssh");
            configFile = Path.of(command.substring("ssh -F '".length(), command.length() - 1));
            String config = Files.readString(configFile);
            assertThat(config)
                    .contains("Host github.com\n")
                    .contains("github.com.pub")
                    .contains("Host * !github.com\n")
                    .contains("deployment.pub")
                    .contains("StrictHostKeyChecking accept-new")
                    .contains("IdentitiesOnly yes")
                    .doesNotContain("bad host");
            assertThat(config.indexOf("Host github.com")).isLessThan(config.indexOf("Host * !github.com"));
            String agentLine = config.lines()
                    .filter(l -> l.strip().startsWith("IdentityAgent"))
                    .findFirst()
                    .orElseThrow();
            socket = Path.of(
                    agentLine.strip().substring("IdentityAgent ".length()).replace("\"", ""));
            assertThat(socket).exists();
            byte[] answer = request(socket, new byte[] {11});
            assertThat(new SshKeys.Reader(Arrays.copyOfRange(answer, 1, answer.length)).uint32())
                    .isEqualTo(2);
            assertThat(Files.readString(configFile.resolveSibling("known_hosts")))
                    .contains("github.com");
            assertThat(legacy).doesNotExist();
            try (var files = Files.walk(workspace)) {
                for (Path file : files.filter(Files::isRegularFile).toList()) {
                    assertThat(Files.readString(file)).as(file.toString()).doesNotContain("PRIVATE KEY");
                }
            }
        }
        assertThat(socket).doesNotExist();
        assertThat(configFile.getParent()).doesNotExist();

        // a person without the deployment key: other hosts get no key and must be pinned
        try (GitEnvironment.Binding binding = GitEnvironment.bind(
                workspace,
                new GitCredentials(Map.of(), null, null, null, Map.of("gitlab.example", personal), ""),
                List.of())) {
            String command = binding.environment().get("GIT_SSH_COMMAND");
            String config = Files.readString(Path.of(command.substring("ssh -F '".length(), command.length() - 1)));
            assertThat(config).contains("Host * !gitlab.example\n  IdentityFile none\n  StrictHostKeyChecking yes");
        }
        // no SSH at all: nothing is started
        try (GitEnvironment.Binding binding = GitEnvironment.bind(workspace, GitCredentials.NONE, List.of())) {
            assertThat(binding.environment()).doesNotContainKey("GIT_SSH_COMMAND");
        }
        assertThat(SshKnownHosts.pinned("github.com", "")).isTrue();
        assertThat(SshKnownHosts.pinned("gitlab.example", "")).isFalse();
        assertThat(SshKnownHosts.pinned("gitlab.example", "gitlab.example ssh-ed25519 AAAA"))
                .isTrue();
        assertThat(SshKnownHosts.pinned("gitlab.example", "@revoked gitlab.example ssh-ed25519 AAAA"))
                .isFalse();
    }

    @Test
    void aSocketPathTooLongForUnixIsRefused() throws Exception {
        Path deep = socketDir().resolve("x".repeat(120));
        assertThatThrownBy(() -> SshAgent.start(deep, List.of())).isInstanceOf(IOException.class);
    }

    // ------------------------------------------------------------------ a real OpenSSH client

    @Test
    void openSshClientsUseTheAgent() throws Exception {
        Assumptions.assumeTrue(onPath("ssh-add") && onPath("ssh-keygen"), "OpenSSH client tools are not installed");
        Path dir = socketDir();
        KeyPair ed = generate("Ed25519", null);
        KeyPair rsa = generate("RSA", 3072);
        SshKeys.Identity edId = SshKeys.parse(openSsh(ed, null));
        SshKeys.Identity rsaId = SshKeys.parse(openSsh(rsa, null));
        Path socket = dir.resolve("a");
        try (SshAgent agent = SshAgent.start(socket, List.of(edId, rsaId))) {
            String listed = run(dir, socket, "ssh-add", "-L");
            assertThat(listed).contains(edId.publicKeyLine()).contains(rsaId.publicKeyLine());
            Path message = Files.writeString(dir.resolve("message"), "signed through the agent\n");
            for (SshKeys.Identity identity : List.of(edId, rsaId)) {
                Path pub = Files.writeString(dir.resolve("key.pub"), identity.publicKeyLine() + "\n");
                Files.deleteIfExists(dir.resolve("message.sig"));
                run(dir, socket, "ssh-keygen", "-Y", "sign", "-f", pub.toString(), "-n", "git", message.toString());
                String allowed = "signer " + identity.publicKeyLine() + "\n";
                Files.writeString(dir.resolve("allowed"), allowed);
                ProcessBuilder check = new ProcessBuilder(
                                "ssh-keygen",
                                "-Y",
                                "verify",
                                "-f",
                                dir.resolve("allowed").toString(),
                                "-I",
                                "signer",
                                "-n",
                                "git",
                                "-s",
                                dir.resolve("message.sig").toString())
                        .redirectInput(message.toFile())
                        .redirectErrorStream(true);
                Process process = check.start();
                String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                assertThat(process.waitFor(30, TimeUnit.SECONDS)).isTrue();
                assertThat(process.exitValue()).as(output).isZero();
            }
        }
    }

    static boolean onPath(String tool) {
        for (String dir : System.getenv().getOrDefault("PATH", "").split(":")) {
            if (Files.isExecutable(Path.of(dir, tool))) {
                return true;
            }
        }
        return false;
    }

    static String run(Path dir, Path socket, String... argv) throws Exception {
        ProcessBuilder builder =
                new ProcessBuilder(argv).directory(dir.toFile()).redirectErrorStream(true);
        builder.environment().put("SSH_AUTH_SOCK", socket.toString());
        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.waitFor(30, TimeUnit.SECONDS)).isTrue();
        assertThat(process.exitValue())
                .as(String.join(" ", argv) + ": " + output)
                .isZero();
        return output;
    }
}
