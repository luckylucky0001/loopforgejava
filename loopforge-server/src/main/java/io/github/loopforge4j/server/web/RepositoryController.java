package io.github.loopforge4j.server.web;

import io.github.loopforge4j.core.domain.RepositoryDefinition;
import io.github.loopforge4j.core.engine.RunNotFoundException;
import io.github.loopforge4j.core.store.CatalogStore;
import io.github.loopforge4j.server.catalog.CatalogService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Git repositories runs can work on (upstream {@code repositoryHandlers}). Runs freeze a copy at
 * start, so editing or deleting a repository never changes a running delivery.
 */
@Tag(name = "Repositories")
@RestController
@RequestMapping("/api/v1/repositories")
class RepositoryController {

    private final CatalogStore store;
    private final CatalogService catalog;

    private final io.github.loopforge4j.server.account.AccessPolicy access;

    RepositoryController(
            CatalogStore store, CatalogService catalog, io.github.loopforge4j.server.account.AccessPolicy access) {
        this.access = access;
        this.store = store;
        this.catalog = catalog;
    }

    @GetMapping
    List<RepositoryDefinition> list() {
        return store.repositories();
    }

    @GetMapping("/{id}")
    RepositoryDefinition get(@PathVariable String id) {
        return store.repository(id).orElseThrow(() -> new RunNotFoundException("repository '" + id + "' not found"));
    }

    @Operation(summary = "Create or replace a repository (credentials belong in server configuration, not the URL)")
    @PutMapping("/{id}")
    RepositoryDefinition put(
            @PathVariable String id, @RequestBody RepositoryDefinition body, java.security.Principal principal) {
        store.repository(id)
                .ifPresent(existing -> access.requireProject(
                        principal, existing.projectId(), io.github.loopforge4j.server.account.ProjectRole.MAINTAINER));
        access.requireProject(principal, body.projectId(), io.github.loopforge4j.server.account.ProjectRole.MAINTAINER);
        return catalog.saveRepository(new RepositoryDefinition(
                id,
                body.name(),
                body.provider(),
                body.cloneUrl(),
                body.webUrl(),
                body.defaultBranch(),
                body.githubRepo(),
                body.projectId(),
                body.updatedAt()));
    }

    @DeleteMapping("/{id}")
    ResponseEntity<Void> delete(@PathVariable String id, java.security.Principal principal) {
        store.repository(id)
                .ifPresent(existing -> access.requireProject(
                        principal, existing.projectId(), io.github.loopforge4j.server.account.ProjectRole.MAINTAINER));
        return store.deleteRepository(id)
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }
}
