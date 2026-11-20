package io.github.loopforge4j.server.sandbox;

import io.github.loopforge4j.core.domain.AssetKind;
import io.github.loopforge4j.core.domain.DockerfileDefinition;
import io.github.loopforge4j.core.domain.EnvironmentDefinition;
import io.github.loopforge4j.core.domain.SandboxProfileDefinition;
import io.github.loopforge4j.core.engine.RunNotFoundException;
import io.github.loopforge4j.core.store.CatalogStore;
import io.github.loopforge4j.server.catalog.CatalogService;
import io.github.loopforge4j.server.web.ApiException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Dockerfiles, environments and sandbox profiles (upstream {@code /api/dockerfiles}, {@code
 * /api/environments}, {@code /api/sandboxes}); writes are for administrators.
 */
@Tag(name = "Sandboxes")
@RestController
@RequestMapping("/api/v1")
class SandboxCatalogController {

    private final CatalogStore store;
    private final CatalogService catalog;
    private final CatalogSandboxes sandboxes;

    SandboxCatalogController(CatalogStore store, CatalogService catalog, CatalogSandboxes sandboxes) {
        this.store = store;
        this.catalog = catalog;
        this.sandboxes = sandboxes;
    }

    // ------------------------------------------------------------------ dockerfiles

    @GetMapping("/dockerfiles")
    List<DockerfileDefinition> dockerfiles() {
        return store.assets(AssetKind.DOCKERFILE);
    }

    @GetMapping("/dockerfiles/{id}")
    DockerfileDefinition dockerfile(@PathVariable String id) {
        return store.asset(AssetKind.DOCKERFILE, id).orElseThrow(() -> notFound("Dockerfile", id));
    }

    @PutMapping("/dockerfiles/{id}")
    DockerfileDefinition saveDockerfile(@PathVariable String id, @RequestBody DockerfileDefinition body) {
        return catalog.saveAsset(
                AssetKind.DOCKERFILE,
                new DockerfileDefinition(id, body.name(), body.description(), body.content(), body.files(), null));
    }

    @Operation(summary = "Delete a Dockerfile; refused while a sandbox profile uses it")
    @DeleteMapping("/dockerfiles/{id}")
    ResponseEntity<Void> deleteDockerfile(@PathVariable String id) {
        List<String> users = store.assets(AssetKind.SANDBOX).stream()
                .filter(p -> id.equals(p.dockerfileId()))
                .map(SandboxProfileDefinition::id)
                .toList();
        refuseWhileUsed("Dockerfile", id, "sandbox profile", users);
        return deleted(store.deleteAsset(AssetKind.DOCKERFILE, id));
    }

    // ------------------------------------------------------------------ environments

    @GetMapping("/environments")
    List<EnvironmentDefinition> environments() {
        return store.assets(AssetKind.ENVIRONMENT);
    }

    @GetMapping("/environments/{id}")
    EnvironmentDefinition environment(@PathVariable String id) {
        return store.asset(AssetKind.ENVIRONMENT, id).orElseThrow(() -> notFound("environment", id));
    }

    @PutMapping("/environments/{id}")
    EnvironmentDefinition saveEnvironment(@PathVariable String id, @RequestBody EnvironmentDefinition body) {
        return catalog.saveAsset(
                AssetKind.ENVIRONMENT,
                new EnvironmentDefinition(
                        id,
                        body.name(),
                        body.description(),
                        body.variables(),
                        body.pathEntries(),
                        body.installScript(),
                        null));
    }

    @Operation(summary = "Delete an environment; refused while a sandbox profile uses it")
    @DeleteMapping("/environments/{id}")
    ResponseEntity<Void> deleteEnvironment(@PathVariable String id) {
        List<String> users = store.assets(AssetKind.SANDBOX).stream()
                .filter(p -> id.equals(p.environmentId()))
                .map(SandboxProfileDefinition::id)
                .toList();
        refuseWhileUsed("environment", id, "sandbox profile", users);
        return deleted(store.deleteAsset(AssetKind.ENVIRONMENT, id));
    }

    // ------------------------------------------------------------------ sandbox profiles

    @Operation(summary = "Catalog sandbox profiles with whether this server can run them now")
    @GetMapping("/sandboxes")
    List<Map<String, Object>> sandboxes() {
        return store.assets(AssetKind.SANDBOX).stream()
                .map(p -> {
                    Map<String, Object> view = new java.util.LinkedHashMap<>(
                            io.github.loopforge4j.core.json.Json.mapper().convertValue(p, Map.class));
                    view.put("available", sandboxes.apply(p.id()).isPresent());
                    view.put("isolated", p.isolated());
                    return view;
                })
                .toList();
    }

    @GetMapping("/sandboxes/{id}")
    SandboxProfileDefinition sandbox(@PathVariable String id) {
        return store.asset(AssetKind.SANDBOX, id).orElseThrow(() -> notFound("sandbox profile", id));
    }

    @PutMapping("/sandboxes/{id}")
    SandboxProfileDefinition saveSandbox(@PathVariable String id, @RequestBody SandboxProfileDefinition body) {
        SandboxProfileDefinition profile = new SandboxProfileDefinition(
                id,
                body.name(),
                body.description(),
                body.driver(),
                body.dockerfileId(),
                body.environmentId(),
                body.readOnlyRootFs(),
                body.environment(),
                null);
        if (profile.dockerfileId() != null
                && store.asset(AssetKind.DOCKERFILE, profile.dockerfileId()).isEmpty()) {
            throw new ApiException(
                    HttpStatus.UNPROCESSABLE_CONTENT, "Dockerfile '" + profile.dockerfileId() + "' not found");
        }
        if (profile.environmentId() != null
                && store.asset(AssetKind.ENVIRONMENT, profile.environmentId()).isEmpty()) {
            throw new ApiException(
                    HttpStatus.UNPROCESSABLE_CONTENT, "environment '" + profile.environmentId() + "' not found");
        }
        return catalog.saveAsset(AssetKind.SANDBOX, profile);
    }

    @Operation(summary = "Delete a sandbox profile; refused while an SOP uses it")
    @DeleteMapping("/sandboxes/{id}")
    ResponseEntity<Void> deleteSandbox(@PathVariable String id) {
        List<String> users = store.workflows().stream()
                .filter(w -> id.equals(w.sandboxProfileId()))
                .map(w -> w.id())
                .toList();
        refuseWhileUsed("sandbox profile", id, "SOP", users);
        return deleted(store.deleteAsset(AssetKind.SANDBOX, id));
    }

    private static void refuseWhileUsed(String what, String id, String userKind, List<String> users) {
        if (!users.isEmpty()) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    what + " '" + id + "' is used by " + userKind + " " + String.join(", ", users));
        }
    }

    private static ResponseEntity<Void> deleted(boolean existed) {
        return existed
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }

    private static RunNotFoundException notFound(String what, String id) {
        return new RunNotFoundException(what + " '" + id + "' not found");
    }
}
