package io.github.loopforge4j.core.runtime.sandbox;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * Configuration of {@link DockerSandboxProvider} (upstream {@code sandbox_docker.go}).
 *
 * @param profile sandbox profile id workflows select ({@code sandboxProfileId})
 * @param root host directory with one workspace per run; mounted at the same absolute path
 * @param image container image; needs {@code sh}, {@code sleep} and the agent CLIs the SOP uses
 * @param docker docker client binary
 * @param network container network ({@code bridge}, {@code none}, a user-defined network)
 * @param memory memory limit, e.g. {@code 4g}; {@code null} for none
 * @param cpus CPU limit, e.g. {@code 2}; {@code null} for none
 * @param pidsLimit process limit, 0 for none
 * @param readOnlyRoot mount the image root read-only (workspace and {@code /tmp} stay writable)
 * @param user {@code uid:gid} processes run as; {@code null} for the server's own ids so workspace
 *     files stay owned by the server
 * @param mountJavaHome mount the server's JDK read-only at the same path so the gate MCP server can
 *     run inside the container (requires a glibc-based image)
 * @param privateHome per-label HOME inside the workspace control directory
 * @param defaultTimeout wall-clock limit when a command does not set one
 */
public record DockerSandboxOptions(
        String profile,
        Path root,
        String image,
        String docker,
        String network,
        String memory,
        String cpus,
        int pidsLimit,
        boolean readOnlyRoot,
        String user,
        boolean mountJavaHome,
        boolean privateHome,
        Duration defaultTimeout) {

    public DockerSandboxOptions {
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(image, "image");
        profile = profile == null || profile.isBlank() ? "docker" : profile;
        docker = docker == null || docker.isBlank() ? "docker" : docker;
        network = network == null || network.isBlank() ? "bridge" : network;
        defaultTimeout = defaultTimeout == null ? Duration.ofHours(2) : defaultTimeout;
    }

    public static DockerSandboxOptions defaults(Path root, String image) {
        return new DockerSandboxOptions(
                "docker", root, image, "docker", "bridge", null, null, 0, false, null, true, true, Duration.ofHours(2));
    }

    /** Host variables the docker client itself needs (never forwarded into the container). */
    static final List<String> CLIENT_ENV = List.of(
            "PATH", "HOME", "DOCKER_HOST", "DOCKER_CONFIG", "DOCKER_CONTEXT", "DOCKER_CERT_PATH", "DOCKER_TLS_VERIFY");
}
