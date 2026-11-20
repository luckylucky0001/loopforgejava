package io.github.loopforge4j.core.domain;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * A sandbox profile SOPs select with {@code sandboxProfileId} (upstream {@code SandboxProfile}).
 *
 * @param driver {@code local} (host processes), {@code native} (bubblewrap isolation on Linux) or
 *     {@code docker} (a container of {@code dockerfileId}'s image, else the configured image)
 * @param environmentId tooling applied to every command, see {@link EnvironmentDefinition}
 * @param readOnlyRootFs the container's root file system is read-only (docker)
 * @param environment further variables of this profile
 */
public record SandboxProfileDefinition(
        String id,
        String name,
        String description,
        String driver,
        String dockerfileId,
        String environmentId,
        boolean readOnlyRootFs,
        Map<String, String> environment,
        Instant updatedAt)
        implements CatalogAsset {

    public static final String LOCAL = "local";
    public static final String NATIVE = "native";
    public static final String DOCKER = "docker";
    public static final Set<String> DRIVERS = Set.of(LOCAL, NATIVE, DOCKER);
    /** Profiles the deployment configures itself; the catalog cannot redefine them. */
    public static final Set<String> BUILT_IN = Set.of("local", "docker", "bwrap");

    public SandboxProfileDefinition {
        id = id == null ? "" : id.strip();
        name = name == null || name.isBlank() ? id : name.strip();
        // upstream saves seatbelt / bwrap as native
        driver = driver == null || driver.isBlank()
                ? LOCAL
                : Set.of("bwrap", "seatbelt").contains(driver.strip()) ? NATIVE : driver.strip();
        dockerfileId = dockerfileId == null || dockerfileId.isBlank() ? null : dockerfileId.strip();
        environmentId = environmentId == null || environmentId.isBlank() ? null : environmentId.strip();
        environment =
                environment == null ? Map.of() : java.util.Collections.unmodifiableMap(new TreeMap<>(environment));
    }

    /** Whether commands are isolated from the host (members may only use isolated profiles). */
    public boolean isolated() {
        return NATIVE.equals(driver) || DOCKER.equals(driver);
    }

    @Override
    public SandboxProfileDefinition withUpdatedAt(Instant instant) {
        return new SandboxProfileDefinition(
                id, name, description, driver, dockerfileId, environmentId, readOnlyRootFs, environment, instant);
    }
}
