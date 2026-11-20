package io.github.loopforge4j.core.runtime.git;

import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A minimal SSH agent (draft-miller-ssh-agent) served from the JVM over a Unix domain socket: it
 * lists its identities and signs with them, nothing else (no adding, removing, locking or
 * extensions). Private keys stay in this process; commands of a run reach only the socket, and only
 * while the agent is open (upstream starts an {@code ssh-agent} for exactly one command).
 */
public final class SshAgent implements AutoCloseable {

    static final int FAILURE = 5;
    static final int REQUEST_IDENTITIES = 11;
    static final int IDENTITIES_ANSWER = 12;
    static final int SIGN_REQUEST = 13;
    static final int SIGN_RESPONSE = 14;
    private static final int MAX_MESSAGE = 256 * 1024;
    /** sun_path is 108 bytes on Linux and 104 on macOS, including the terminating NUL. */
    public static final int MAX_SOCKET_PATH = 103;

    private final Path socket;
    private final List<SshKeys.Identity> identities;
    private final ServerSocketChannel server;
    private final Set<SocketChannel> clients = ConcurrentHashMap.newKeySet();
    private volatile boolean closed;

    private SshAgent(Path socket, List<SshKeys.Identity> identities, ServerSocketChannel server) {
        this.socket = socket;
        this.identities = List.copyOf(identities);
        this.server = server;
    }

    /**
     * Serves {@code identities} on a new socket at {@code socket} (readable and writable by its
     * owner only).
     *
     * @throws IOException when the path is too long for a Unix socket or cannot be bound
     */
    public static SshAgent start(Path socket, List<SshKeys.Identity> identities) throws IOException {
        if (socket.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_SOCKET_PATH) {
            throw new IOException("SSH agent socket path is too long: " + socket);
        }
        ServerSocketChannel server = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
        try {
            server.bind(UnixDomainSocketAddress.of(socket));
            restrict(socket);
        } catch (IOException | RuntimeException e) {
            server.close();
            Files.deleteIfExists(socket);
            throw e;
        }
        SshAgent agent = new SshAgent(socket, identities, server);
        Thread.ofVirtual().name("ssh-agent-" + socket.getFileName()).start(agent::accept);
        return agent;
    }

    /** Owner-only, never through a link someone put in the socket's place meanwhile. */
    private static void restrict(Path socket) throws IOException {
        var permissions = PosixFilePermissions.fromString("rw-------");
        try {
            Files.getFileAttributeView(
                            socket,
                            java.nio.file.attribute.PosixFileAttributeView.class,
                            java.nio.file.LinkOption.NOFOLLOW_LINKS)
                    .setPermissions(permissions);
        } catch (UnsupportedOperationException | IOException e) {
            // Linux cannot change a non-link's mode without following: check it is no link first
            if (Files.readAttributes(
                            socket,
                            java.nio.file.attribute.BasicFileAttributes.class,
                            java.nio.file.LinkOption.NOFOLLOW_LINKS)
                    .isSymbolicLink()) {
                throw new IOException("the agent socket was replaced by a link: " + socket);
            }
            Files.setPosixFilePermissions(socket, permissions);
        }
    }

    public Path socket() {
        return socket;
    }

    private void accept() {
        while (!closed) {
            try {
                SocketChannel client = server.accept();
                clients.add(client);
                Thread.ofVirtual().name("ssh-agent-client").start(() -> serve(client));
            } catch (IOException e) {
                return;
            }
        }
    }

    private void serve(SocketChannel client) {
        try (client) {
            while (!closed) {
                ByteBuffer header = ByteBuffer.allocate(4);
                if (!fill(client, header)) {
                    return;
                }
                int length = header.flip().getInt();
                if (length <= 0 || length > MAX_MESSAGE) {
                    return;
                }
                ByteBuffer body = ByteBuffer.allocate(length);
                if (!fill(client, body)) {
                    return;
                }
                byte[] reply = handle(body.array());
                ByteBuffer out = ByteBuffer.allocate(4 + reply.length)
                        .putInt(reply.length)
                        .put(reply)
                        .flip();
                while (out.hasRemaining()) {
                    client.write(out);
                }
            }
        } catch (IOException e) {
            // the client went away
        } finally {
            clients.remove(client);
        }
    }

    private static boolean fill(SocketChannel client, ByteBuffer buffer) throws IOException {
        while (buffer.hasRemaining()) {
            if (client.read(buffer) < 0) {
                return false;
            }
        }
        return true;
    }

    /** One request to one reply (both without the length prefix). */
    byte[] handle(byte[] message) {
        try {
            int type = message[0] & 0xff;
            SshKeys.Reader in = new SshKeys.Reader(Arrays.copyOfRange(message, 1, message.length));
            if (type == REQUEST_IDENTITIES) {
                SshKeys.Wire out = new SshKeys.Wire().raw(IDENTITIES_ANSWER).uint32(identities.size());
                for (SshKeys.Identity identity : identities) {
                    out.string(identity.publicBlob()).string("loopforge");
                }
                return out.bytes();
            }
            if (type == SIGN_REQUEST) {
                byte[] blob = in.bytes();
                byte[] data = in.bytes();
                int flags = (int) in.uint32();
                for (SshKeys.Identity identity : identities) {
                    if (Arrays.equals(identity.publicBlob(), blob)) {
                        return new SshKeys.Wire()
                                .raw(SIGN_RESPONSE)
                                .string(identity.sign(data, flags))
                                .bytes();
                    }
                }
            }
        } catch (RuntimeException | java.security.GeneralSecurityException e) {
            // malformed request or a key the JDK cannot use: refuse like an agent without the key
        }
        return new byte[] {FAILURE};
    }

    @Override
    public void close() {
        closed = true;
        try {
            server.close();
        } catch (IOException e) {
            // already closed
        }
        for (SocketChannel client : clients) {
            try {
                client.close();
            } catch (IOException e) {
                // already closed
            }
        }
        try {
            Files.deleteIfExists(socket);
        } catch (IOException e) {
            // the workspace is going away anyway
        }
    }
}
