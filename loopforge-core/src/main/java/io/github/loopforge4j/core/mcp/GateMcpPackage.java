package io.github.loopforge4j.core.mcp;

import io.github.loopforge4j.core.domain.McpServerDefinition;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

/**
 * Writes the gate MCP server as a self-contained jar into a run workspace and describes how a CLI
 * starts it (upstream {@code materializeGateMCP}, which ships a Go binary instead).
 */
public final class GateMcpPackage {

    /** Classes the jar must contain; {@code GateMcpServerJarTest} fails when one is missing. */
    private static final List<Class<?>> CLASSES = List.of(GateMcpServer.class, MiniJson.class);

    private static volatile byte[] jar;

    private GateMcpPackage() {}

    /**
     * Ensures {@code target} holds the jar (atomically replaced when the content differs) and returns
     * the stdio server definition that writes to {@code resultPath}.
     *
     * @param javaBinary absolute path of a {@code java} launcher visible inside the sandbox
     */
    public static McpServerDefinition materialize(Path target, Path javaBinary, Path resultPath) throws IOException {
        byte[] content = jarBytes();
        if (!Files.exists(target) || !java.util.Arrays.equals(Files.readAllBytes(target), content)) {
            Files.createDirectories(target.getParent());
            Path tmp = Files.createTempFile(target.getParent(), ".gate-mcp", ".jar");
            Files.write(tmp, content);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        }
        return new McpServerDefinition(
                GateMcpServer.SERVER_ID,
                "LoopForge Gate",
                "Submits the node's terminal decision",
                McpServerDefinition.STDIO,
                javaBinary.toString(),
                List.of(
                        "-Xshare:auto",
                        "-XX:TieredStopAtLevel=1",
                        "-XX:+UseSerialGC",
                        "-Xmx64m",
                        "-Dfile.encoding=UTF-8",
                        "-jar",
                        target.toString()),
                null,
                Map.of(GateMcpServer.RESULT_PATH_ENV, resultPath.toString()),
                null,
                null,
                0,
                null);
    }

    /** The {@code java} launcher of the running JVM. */
    public static Path currentJava() {
        return Path.of(System.getProperty("java.home"), "bin", "java");
    }

    static byte[] jarBytes() {
        byte[] cached = jar;
        if (cached != null) {
            return cached;
        }
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, GateMcpServer.class.getName());
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (JarOutputStream out = new JarOutputStream(buffer, manifest)) {
            for (Class<?> type : CLASSES) {
                String entry = type.getName().replace('.', '/') + ".class";
                try (InputStream in = type.getClassLoader().getResourceAsStream(entry)) {
                    if (in == null) {
                        throw new IOException("class resource " + entry + " not found");
                    }
                    JarEntry jarEntry = new JarEntry(entry);
                    jarEntry.setTime(0);
                    out.putNextEntry(jarEntry);
                    in.transferTo(out);
                    out.closeEntry();
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        jar = buffer.toByteArray();
        return jar;
    }
}
