package io.github.loopforge4j.core.runtime.git;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPrivateKeySpec;
import java.security.spec.EdECPrivateKeySpec;
import java.security.spec.NamedParameterSpec;
import java.security.spec.RSAPrivateCrtKeySpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

/**
 * Unencrypted SSH private keys (OpenSSH, PKCS#1, SEC1 and PKCS#8 PEM) for the in-process {@link
 * SshAgent}: Ed25519, RSA and ECDSA (NIST P-256/384/521). A key protected by a passphrase cannot be
 * used unattended and is refused, as are other key types.
 */
public final class SshKeys {

    /** Upstream's limit for a stored key. */
    public static final int MAX_LENGTH = 16 * 1024;

    private SshKeys() {}

    /** A usable SSH identity: its public key blob (wire format) and a signer. */
    public static final class Identity {
        private final String type;
        private final byte[] publicBlob;
        private final PrivateKey key;
        private final String curve;
        private final String comment;

        Identity(String type, byte[] publicBlob, PrivateKey key, String curve, String comment) {
            this.type = type;
            this.publicBlob = publicBlob.clone();
            this.key = key;
            this.curve = curve;
            this.comment = comment == null ? "" : comment;
        }

        /** {@code ssh-ed25519}, {@code ssh-rsa} or {@code ecdsa-sha2-nistp*}. */
        public String type() {
            return type;
        }

        public byte[] publicBlob() {
            return publicBlob.clone();
        }

        public String comment() {
            return comment;
        }

        /** The public key as one {@code authorized_keys} line. */
        public String publicKeyLine() {
            return type + " " + Base64.getEncoder().encodeToString(publicBlob);
        }

        /** {@code SHA256:…} as printed by {@code ssh-keygen -l}. */
        public String fingerprint() {
            try {
                byte[] digest = MessageDigest.getInstance("SHA-256").digest(publicBlob);
                return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest);
            } catch (GeneralSecurityException e) {
                throw new IllegalStateException(e);
            }
        }

        /**
         * Signs {@code data} as the agent protocol asks: RSA honours the SHA-2 flags (2 = rsa-sha2-256,
         * 4 = rsa-sha2-512), ECDSA signs with its curve's hash.
         *
         * @return the signature blob (algorithm name and signature)
         */
        public byte[] sign(byte[] data, int flags) throws GeneralSecurityException {
            Wire out = new Wire();
            switch (type) {
                case "ssh-ed25519" -> {
                    Signature signature = Signature.getInstance("Ed25519");
                    signature.initSign(key);
                    signature.update(data);
                    out.string("ssh-ed25519").string(signature.sign());
                }
                case "ssh-rsa" -> {
                    String algorithm =
                            (flags & 4) != 0 ? "rsa-sha2-512" : (flags & 2) != 0 ? "rsa-sha2-256" : "ssh-rsa";
                    String jca = switch (algorithm) {
                        case "rsa-sha2-512" -> "SHA512withRSA";
                        case "rsa-sha2-256" -> "SHA256withRSA";
                        default -> "SHA1withRSA";
                    };
                    Signature signature = Signature.getInstance(jca);
                    signature.initSign(key);
                    signature.update(data);
                    out.string(algorithm).string(signature.sign());
                }
                default -> {
                    String hash = switch (curve) {
                        case "nistp256" -> "SHA256";
                        case "nistp384" -> "SHA384";
                        default -> "SHA512";
                    };
                    Signature signature = Signature.getInstance(hash + "withECDSA");
                    signature.initSign(key);
                    signature.update(data);
                    BigInteger[] rs = derSignature(signature.sign());
                    out.string(type).string(new Wire().mpint(rs[0]).mpint(rs[1]).bytes());
                }
            }
            return out.bytes();
        }

        @Override
        public String toString() {
            return "Identity[" + type + " " + fingerprint() + "]";
        }
    }

    /** A key that cannot be used, with a message for the person who supplied it. */
    public static final class InvalidKeyException extends IllegalArgumentException {
        InvalidKeyException(String message) {
            super(message);
        }
    }

    /** Trims and normalises line endings; refuses oversized text. */
    public static String normalize(String text) {
        if (text == null || text.isBlank()) {
            throw new InvalidKeyException("请粘贴 Git SSH 私钥");
        }
        String value = text.replace("\r\n", "\n").strip() + "\n";
        if (value.length() > MAX_LENGTH) {
            throw new InvalidKeyException("Git SSH 私钥不能超过 16 KiB");
        }
        return value;
    }

    /** Parses one private key. */
    public static Identity parse(String text) {
        String value = normalize(text);
        try {
            if (value.contains("-----BEGIN OPENSSH PRIVATE KEY-----")) {
                return openSsh(pem(value, "OPENSSH PRIVATE KEY"));
            }
            if (value.contains("-----BEGIN ENCRYPTED PRIVATE KEY-----") || value.contains("Proc-Type: 4,ENCRYPTED")) {
                throw passphrase();
            }
            if (value.contains("-----BEGIN RSA PRIVATE KEY-----")) {
                return pkcs1(pem(value, "RSA PRIVATE KEY"));
            }
            if (value.contains("-----BEGIN EC PRIVATE KEY-----")) {
                return sec1(pem(value, "EC PRIVATE KEY"), null);
            }
            if (value.contains("-----BEGIN PRIVATE KEY-----")) {
                return pkcs8(pem(value, "PRIVATE KEY"));
            }
        } catch (InvalidKeyException e) {
            throw e;
        } catch (GeneralSecurityException | RuntimeException e) {
            throw new InvalidKeyException("Git SSH 私钥格式无效，请粘贴完整的 OpenSSH 或 PEM 私钥");
        }
        throw new InvalidKeyException("Git SSH 私钥格式无效，请粘贴完整的 OpenSSH 或 PEM 私钥");
    }

    private static InvalidKeyException passphrase() {
        return new InvalidKeyException("Git SSH 密钥需要口令，无法用于无人值守；请使用单独生成的无口令部署密钥");
    }

    private static byte[] pem(String text, String label) {
        String begin = "-----BEGIN " + label + "-----";
        String end = "-----END " + label + "-----";
        int from = text.indexOf(begin);
        int to = text.indexOf(end, from);
        if (from < 0 || to < 0) {
            throw new InvalidKeyException("Git SSH 私钥不完整");
        }
        StringBuilder body = new StringBuilder();
        for (String line : text.substring(from + begin.length(), to).split("\n")) {
            if (!line.contains(":")) {
                body.append(line.strip());
            }
        }
        return Base64.getMimeDecoder().decode(body.toString());
    }

    // ------------------------------------------------------------------ OpenSSH

    private static final byte[] MAGIC = "openssh-key-v1\0".getBytes(StandardCharsets.US_ASCII);

    private static Identity openSsh(byte[] data) throws GeneralSecurityException {
        if (data.length < MAGIC.length || !Arrays.equals(Arrays.copyOf(data, MAGIC.length), MAGIC)) {
            throw new InvalidKeyException("Git SSH 私钥格式无效");
        }
        Reader in = new Reader(Arrays.copyOfRange(data, MAGIC.length, data.length));
        String cipher = in.text();
        String kdf = in.text();
        in.bytes(); // kdf options
        if (!"none".equals(cipher) || !"none".equals(kdf)) {
            throw passphrase();
        }
        if (in.uint32() != 1) {
            throw new InvalidKeyException("一个文件里只能有一把 Git SSH 私钥");
        }
        in.bytes(); // public key (repeated in the private section)
        Reader p = new Reader(in.bytes());
        if (p.uint32() != p.uint32()) {
            throw passphrase();
        }
        String type = p.text();
        switch (type) {
            case "ssh-ed25519" -> {
                byte[] pub = p.bytes();
                byte[] secret = p.bytes();
                String comment = p.text();
                if (pub.length != 32 || secret.length != 64) {
                    throw new InvalidKeyException("Git SSH 私钥格式无效");
                }
                return ed25519(Arrays.copyOf(secret, 32), pub, comment);
            }
            case "ssh-rsa" -> {
                BigInteger n = p.mpint();
                BigInteger e = p.mpint();
                BigInteger d = p.mpint();
                BigInteger iqmp = p.mpint();
                BigInteger pp = p.mpint();
                BigInteger q = p.mpint();
                return rsa(n, e, d, pp, q, iqmp, p.text());
            }
            case "ecdsa-sha2-nistp256", "ecdsa-sha2-nistp384", "ecdsa-sha2-nistp521" -> {
                String curve = p.text();
                byte[] q = p.bytes();
                BigInteger d = p.mpint();
                return ecdsa(curve, q, d, p.text());
            }
            default -> throw unsupported(type);
        }
    }

    private static InvalidKeyException unsupported(String type) {
        return new InvalidKeyException("不支持的 Git SSH 密钥类型 " + type + "，请使用 ed25519、RSA 或 ECDSA 密钥");
    }

    // ------------------------------------------------------------------ PEM

    private static Identity pkcs1(byte[] der) throws GeneralSecurityException {
        List<Der> fields = Der.read(der).children();
        // version, n, e, d, p, q, dp, dq, qinv
        return rsa(
                fields.get(1).integer(),
                fields.get(2).integer(),
                fields.get(3).integer(),
                fields.get(4).integer(),
                fields.get(5).integer(),
                fields.get(8).integer(),
                "");
    }

    private static Identity sec1(byte[] der, String curveOid) throws GeneralSecurityException {
        List<Der> fields = Der.read(der).children();
        BigInteger d = new BigInteger(1, fields.get(1).content());
        String oid = curveOid;
        byte[] q = null;
        for (Der field : fields.subList(2, fields.size())) {
            if (field.tag() == 0xA0) {
                oid = Der.read(field.content()).oid();
            } else if (field.tag() == 0xA1) {
                byte[] bits = Der.read(field.content()).content();
                q = Arrays.copyOfRange(bits, 1, bits.length);
            }
        }
        String curve = switch (oid == null ? "" : oid) {
            case "1.2.840.10045.3.1.7" -> "nistp256";
            case "1.3.132.0.34" -> "nistp384";
            case "1.3.132.0.35" -> "nistp521";
            default -> throw unsupported("EC " + oid);
        };
        if (q == null) {
            // keys written by the JDK leave the public point out: derive it (Q = d·G)
            q = ecPublic(curve, d);
        }
        return ecdsa(curve, q, d, "");
    }

    /** The uncompressed public point of {@code d} on a NIST prime curve. */
    static byte[] ecPublic(String curve, BigInteger d) throws GeneralSecurityException {
        ECParameterSpec spec = curveSpec(curve);
        BigInteger p = ((java.security.spec.ECFieldFp) spec.getCurve().getField()).getP();
        BigInteger a = spec.getCurve().getA();
        java.security.spec.ECPoint result = java.security.spec.ECPoint.POINT_INFINITY;
        java.security.spec.ECPoint addend = spec.getGenerator();
        for (int i = 0; i < d.bitLength(); i++) {
            if (d.testBit(i)) {
                result = add(result, addend, p, a);
            }
            addend = add(addend, addend, p, a);
        }
        int size = (spec.getCurve().getField().getFieldSize() + 7) / 8;
        byte[] out = new byte[1 + 2 * size];
        out[0] = 4;
        copyUnsigned(result.getAffineX(), out, 1, size);
        copyUnsigned(result.getAffineY(), out, 1 + size, size);
        return out;
    }

    private static java.security.spec.ECPoint add(
            java.security.spec.ECPoint s, java.security.spec.ECPoint t, BigInteger p, BigInteger a) {
        if (s.equals(java.security.spec.ECPoint.POINT_INFINITY)) {
            return t;
        }
        if (t.equals(java.security.spec.ECPoint.POINT_INFINITY)) {
            return s;
        }
        BigInteger lambda;
        if (s.getAffineX().equals(t.getAffineX())) {
            if (!s.getAffineY().equals(t.getAffineY()) || s.getAffineY().signum() == 0) {
                return java.security.spec.ECPoint.POINT_INFINITY;
            }
            BigInteger x = s.getAffineX();
            lambda = x.pow(2)
                    .multiply(BigInteger.valueOf(3))
                    .add(a)
                    .multiply(s.getAffineY().shiftLeft(1).modInverse(p))
                    .mod(p);
        } else {
            lambda = t.getAffineY()
                    .subtract(s.getAffineY())
                    .multiply(t.getAffineX().subtract(s.getAffineX()).modInverse(p))
                    .mod(p);
        }
        BigInteger x =
                lambda.pow(2).subtract(s.getAffineX()).subtract(t.getAffineX()).mod(p);
        BigInteger y = lambda.multiply(s.getAffineX().subtract(x))
                .subtract(s.getAffineY())
                .mod(p);
        return new java.security.spec.ECPoint(x, y);
    }

    private static void copyUnsigned(BigInteger value, byte[] out, int offset, int size) {
        byte[] bytes = value.toByteArray();
        int skip = bytes.length > size ? bytes.length - size : 0;
        System.arraycopy(bytes, skip, out, offset + size - (bytes.length - skip), bytes.length - skip);
    }

    private static ECParameterSpec curveSpec(String curve) throws GeneralSecurityException {
        String jcaCurve = switch (curve) {
            case "nistp256" -> "secp256r1";
            case "nistp384" -> "secp384r1";
            case "nistp521" -> "secp521r1";
            default -> throw unsupported("ecdsa " + curve);
        };
        java.security.AlgorithmParameters parameters = java.security.AlgorithmParameters.getInstance("EC");
        parameters.init(new ECGenParameterSpec(jcaCurve));
        return parameters.getParameterSpec(ECParameterSpec.class);
    }

    private static Identity pkcs8(byte[] der) throws GeneralSecurityException {
        List<Der> fields = Der.read(der).children();
        List<Der> algorithm = fields.get(1).children();
        String oid = algorithm.get(0).oid();
        byte[] inner = fields.get(2).content();
        return switch (oid) {
            case "1.2.840.113549.1.1.1" -> pkcs1(inner);
            case "1.2.840.10045.2.1" -> sec1(inner, algorithm.get(1).oid());
            case "1.3.101.112" -> {
                byte[] seed = Der.read(inner).content();
                yield ed25519(seed, ed25519Public(seed), "");
            }
            default -> throw unsupported(oid);
        };
    }

    // ------------------------------------------------------------------ key construction

    private static Identity ed25519(byte[] seed, byte[] pub, String comment) throws GeneralSecurityException {
        PrivateKey key = KeyFactory.getInstance("Ed25519")
                .generatePrivate(new EdECPrivateKeySpec(NamedParameterSpec.ED25519, seed));
        byte[] blob = new Wire().string("ssh-ed25519").string(pub).bytes();
        return new Identity("ssh-ed25519", blob, key, null, comment);
    }

    /** The Ed25519 public key of {@code seed}: the JDK derives it from the 32 "random" bytes. */
    private static byte[] ed25519Public(byte[] seed) throws GeneralSecurityException {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("Ed25519");
        generator.initialize(NamedParameterSpec.ED25519, new SecureRandom() {
            @Override
            public void nextBytes(byte[] bytes) {
                System.arraycopy(seed, 0, bytes, 0, Math.min(seed.length, bytes.length));
            }
        });
        byte[] x509 = generator.generateKeyPair().getPublic().getEncoded();
        return Arrays.copyOfRange(x509, x509.length - 32, x509.length);
    }

    private static Identity rsa(
            BigInteger n, BigInteger e, BigInteger d, BigInteger p, BigInteger q, BigInteger iqmp, String comment)
            throws GeneralSecurityException {
        if (n.bitLength() < 2048) {
            throw new InvalidKeyException("RSA 密钥至少需要 2048 位");
        }
        PrivateKey key = KeyFactory.getInstance("RSA")
                .generatePrivate(new RSAPrivateCrtKeySpec(
                        n, e, d, p, q, d.mod(p.subtract(BigInteger.ONE)), d.mod(q.subtract(BigInteger.ONE)), iqmp));
        byte[] blob = new Wire().string("ssh-rsa").mpint(e).mpint(n).bytes();
        return new Identity("ssh-rsa", blob, key, null, comment);
    }

    private static Identity ecdsa(String curve, byte[] q, BigInteger d, String comment)
            throws GeneralSecurityException {
        ECParameterSpec spec = curveSpec(curve);
        PrivateKey key = KeyFactory.getInstance("EC").generatePrivate(new ECPrivateKeySpec(d, spec));
        String type = "ecdsa-sha2-" + curve.toLowerCase(Locale.ROOT);
        byte[] blob = new Wire().string(type).string(curve).string(q).bytes();
        return new Identity(type, blob, key, curve, comment);
    }

    /** {@code SEQUENCE { INTEGER r, INTEGER s }} as produced by the JDK. */
    static BigInteger[] derSignature(byte[] der) {
        List<Der> fields = Der.read(der).children();
        return new BigInteger[] {fields.get(0).integer(), fields.get(1).integer()};
    }

    // ------------------------------------------------------------------ encodings

    /** SSH wire format writer. */
    static final class Wire {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();

        Wire uint32(long value) {
            out.write((int) (value >>> 24) & 0xff);
            out.write((int) (value >>> 16) & 0xff);
            out.write((int) (value >>> 8) & 0xff);
            out.write((int) value & 0xff);
            return this;
        }

        Wire string(byte[] value) {
            uint32(value.length);
            out.writeBytes(value);
            return this;
        }

        Wire string(String value) {
            return string(value.getBytes(StandardCharsets.UTF_8));
        }

        Wire mpint(BigInteger value) {
            return string(value.signum() == 0 ? new byte[0] : value.toByteArray());
        }

        Wire raw(int b) {
            out.write(b);
            return this;
        }

        byte[] bytes() {
            return out.toByteArray();
        }
    }

    /** SSH wire format reader; every read is bounds-checked. */
    static final class Reader {
        private final byte[] data;
        private int at;

        Reader(byte[] data) {
            this.data = data;
        }

        long uint32() {
            if (at + 4 > data.length) {
                throw new IllegalArgumentException("truncated");
            }
            long value = ((data[at] & 0xffL) << 24)
                    | ((data[at + 1] & 0xffL) << 16)
                    | ((data[at + 2] & 0xffL) << 8)
                    | (data[at + 3] & 0xffL);
            at += 4;
            return value;
        }

        byte[] bytes() {
            long length = uint32();
            if (length > data.length - at) {
                throw new IllegalArgumentException("truncated");
            }
            byte[] value = Arrays.copyOfRange(data, at, at + (int) length);
            at += (int) length;
            return value;
        }

        String text() {
            return new String(bytes(), StandardCharsets.UTF_8);
        }

        BigInteger mpint() {
            byte[] value = bytes();
            return value.length == 0 ? BigInteger.ZERO : new BigInteger(value);
        }

        int remaining() {
            return data.length - at;
        }
    }

    /** A minimal DER reader for the PEM key structures. */
    record Der(int tag, byte[] content) {

        static Der read(byte[] data) {
            return read(data, new int[] {0});
        }

        private static Der read(byte[] data, int[] at) {
            int tag = data[at[0]++] & 0xff;
            int length = data[at[0]++] & 0xff;
            if (length > 0x80) {
                int count = length & 0x7f;
                if (count > 4) {
                    throw new IllegalArgumentException("DER length");
                }
                length = 0;
                for (int i = 0; i < count; i++) {
                    length = (length << 8) | (data[at[0]++] & 0xff);
                }
            } else if (length == 0x80) {
                throw new IllegalArgumentException("indefinite DER length");
            }
            if (length < 0 || at[0] + length > data.length) {
                throw new IllegalArgumentException("truncated DER");
            }
            byte[] content = Arrays.copyOfRange(data, at[0], at[0] + length);
            at[0] += length;
            return new Der(tag, content);
        }

        List<Der> children() {
            List<Der> out = new java.util.ArrayList<>();
            int[] at = {0};
            while (at[0] < content.length) {
                out.add(read(content, at));
            }
            return out;
        }

        BigInteger integer() {
            return new BigInteger(content);
        }

        String oid() {
            StringBuilder out = new StringBuilder();
            out.append(content[0] / 40).append('.').append(content[0] % 40);
            long value = 0;
            for (int i = 1; i < content.length; i++) {
                value = (value << 7) | (content[i] & 0x7f);
                if ((content[i] & 0x80) == 0) {
                    out.append('.').append(value);
                    value = 0;
                }
            }
            return out.toString();
        }
    }
}
