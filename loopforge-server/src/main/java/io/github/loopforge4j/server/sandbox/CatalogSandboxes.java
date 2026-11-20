package io.github.loopforge4j.server.sandbox;

import io.github.loopforge4j.core.domain.AssetKind;
import io.github.loopforge4j.core.domain.DockerfileDefinition;
import io.github.loopforge4j.core.domain.EnvironmentDefinition;
import io.github.loopforge4j.core.domain.SandboxProfileDefinition;
import io.github.loopforge4j.core.runtime.sandbox.BwrapSandboxProvider;
import io.github.loopforge4j.core.runtime.sandbox.DockerSandboxOptions;
import io.github.loopforge4j.core.runtime.sandbox.DockerSandboxProvider;
import io.github.loopforge4j.core.runtime.sandbox.LocalSandboxProvider;
import io.github.loopforge4j.core.runtime.sandbox.ProfileSandboxProvider;
import io.github.loopforge4j.core.runtime.sandbox.SandboxProvider;
import io.github.loopforge4j.core.store.CatalogStore;
import io.github.loopforge4j.server.config.LoopForgeProperties;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Sandbox profiles defined in the catalog (upstream sandbox profiles): each resolves to the driver's
 * provider (the local one, bubblewrap, or Docker with the profile's image) plus its tooling.
 * Providers are cached until the profile, its environment or its Dockerfile changes.
 */
@Component
public class CatalogSandboxes implements Function<String, Optional<SandboxProvider>> {

    private final CatalogStore catalog;
    private final ObjectProvider<LocalSandboxProvider> local;
    private final ObjectProvider<BwrapSandboxProvider> bwrap;
    private final DockerImages images;
    private final LoopForgeProperties properties;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    private record Cached(List<Instant> versions, SandboxProvider provider) {}

    public CatalogSandboxes(
            CatalogStore catalog,
            ObjectProvider<LocalSandboxProvider> local,
            ObjectProvider<BwrapSandboxProvider> bwrap,
            DockerImages images,
            LoopForgeProperties properties) {
        this.catalog = catalog;
        this.local = local;
        this.bwrap = bwrap;
        this.images = images;
        this.properties = properties;
    }

    @Override
    public Optional<SandboxProvider> apply(String profileId) {
        if (profileId == null || SandboxProfileDefinition.BUILT_IN.contains(profileId)) {
            return Optional.empty();
        }
        Optional<SandboxProfileDefinition> profile = catalog.asset(AssetKind.SANDBOX, profileId);
        if (profile.isEmpty()) {
            return Optional.empty();
        }
        SandboxProfileDefinition p = profile.get();
        Optional<EnvironmentDefinition> environment =
                p.environmentId() == null ? Optional.empty() : catalog.asset(AssetKind.ENVIRONMENT, p.environmentId());
        Optional<DockerfileDefinition> dockerfile =
                p.dockerfileId() == null ? Optional.empty() : catalog.asset(AssetKind.DOCKERFILE, p.dockerfileId());
        if (p.environmentId() != null && environment.isEmpty() || p.dockerfileId() != null && dockerfile.isEmpty()) {
            return Optional.empty(); // a reference that no longer resolves makes the profile unavailable
        }
        List<Instant> versions = java.util.Arrays.asList(
                p.updatedAt(),
                environment.map(EnvironmentDefinition::updatedAt).orElse(null),
                dockerfile.map(DockerfileDefinition::updatedAt).orElse(null));
        Cached cached = cache.get(profileId);
        if (cached != null && Objects.equals(cached.versions(), versions)) {
            return Optional.of(cached.provider());
        }
        Optional<SandboxProvider> built = build(p, environment, dockerfile);
        built.ifPresentOrElse(
                provider -> cache.put(profileId, new Cached(versions, provider)), () -> cache.remove(profileId));
        return built;
    }

    private Optional<SandboxProvider> build(
            SandboxProfileDefinition p,
            Optional<EnvironmentDefinition> environment,
            Optional<DockerfileDefinition> dockerfile) {
        SandboxProvider driver;
        ProfileSandboxProvider.Preparation preparation = null;
        switch (p.driver()) {
            case SandboxProfileDefinition.LOCAL -> driver = local.getObject();
            case SandboxProfileDefinition.NATIVE -> {
                BwrapSandboxProvider provider = bwrap.getIfAvailable();
                if (provider == null) {
                    return Optional.empty();
                }
                driver = provider;
            }
            case SandboxProfileDefinition.DOCKER -> {
                LoopForgeProperties.Docker docker = properties.sandbox().docker();
                if (!docker.enabled()) {
                    return Optional.empty();
                }
                String image = dockerfile.map(DockerImages::tag).orElse(docker.image());
                driver = new DockerSandboxProvider(new DockerSandboxOptions(
                        p.id(),
                        properties.workspaceRoot(),
                        image,
                        docker.binary(),
                        docker.network(),
                        docker.memory(),
                        docker.cpus(),
                        docker.pidsLimit(),
                        docker.readOnlyRoot() || p.readOnlyRootFs(),
                        docker.user(),
                        docker.mountJavaHome(),
                        properties.sandbox().privateHome(),
                        properties.engine().nodeTimeout()));
                if (dockerfile.isPresent()) {
                    DockerfileDefinition definition = dockerfile.get();
                    preparation = () -> images.ensure(definition);
                }
            }
            default -> {
                return Optional.empty();
            }
        }
        Map<String, String> variables = new LinkedHashMap<>(
                environment.map(EnvironmentDefinition::variables).orElse(Map.of()));
        variables.putAll(p.environment());
        return Optional.of(new ProfileSandboxProvider(
                p.id(),
                driver,
                preparation,
                environment.map(EnvironmentDefinition::id).orElse(null),
                variables,
                environment.map(EnvironmentDefinition::pathEntries).orElse(List.of()),
                environment.map(EnvironmentDefinition::installScript).orElse(null)));
    }

    /** Whether commands of the profile are isolated from the host. */
    public boolean isolated(String profileId) {
        return catalog.asset(AssetKind.SANDBOX, profileId == null ? "" : profileId)
                .map(SandboxProfileDefinition::isolated)
                .orElse(false);
    }
}
