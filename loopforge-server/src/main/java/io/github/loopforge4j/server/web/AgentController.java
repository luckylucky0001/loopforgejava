package io.github.loopforge4j.server.web;

import io.github.loopforge4j.core.domain.AgentDefinition;
import io.github.loopforge4j.core.engine.RunNotFoundException;
import io.github.loopforge4j.core.store.CatalogStore;
import io.github.loopforge4j.server.catalog.CatalogService;
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

/** Roles (upstream {@code catalogHandlers}, agent part). */
@Tag(name = "Agents")
@RestController
@RequestMapping("/api/v1/agents")
class AgentController {

    private final CatalogStore store;
    private final CatalogService catalog;

    AgentController(CatalogStore store, CatalogService catalog) {
        this.store = store;
        this.catalog = catalog;
    }

    @GetMapping
    List<AgentDefinition> list() {
        return store.agents();
    }

    @GetMapping("/{id}")
    AgentDefinition get(@PathVariable String id) {
        return store.agent(id).orElseThrow(() -> new RunNotFoundException("agent '" + id + "' not found"));
    }

    @PutMapping("/{id}")
    AgentDefinition put(@PathVariable String id, @RequestBody AgentDefinition body) {
        AgentDefinition agent = id.equals(body.id())
                ? body
                : new AgentDefinition(
                        id,
                        body.name(),
                        body.version(),
                        body.description(),
                        body.prompt(),
                        body.rules(),
                        body.skills(),
                        body.mcpServers(),
                        body.hooks(),
                        body.executorId(),
                        body.model(),
                        body.effort(),
                        body.loop(),
                        body.interventions(),
                        body.artifactOutputs(),
                        body.verification(),
                        body.updatedAt());
        return catalog.saveAgent(agent);
    }

    @DeleteMapping("/{id}")
    ResponseEntity<Void> delete(@PathVariable String id) {
        return store.deleteAgent(id)
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }
}
