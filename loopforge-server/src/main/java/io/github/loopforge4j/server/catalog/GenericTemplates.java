package io.github.loopforge4j.server.catalog;

import io.github.loopforge4j.core.domain.AgentDefinition;
import io.github.loopforge4j.core.domain.SopLanes;
import io.github.loopforge4j.core.domain.WorkflowDefinition;
import io.github.loopforge4j.core.domain.WorkflowNode;
import io.github.loopforge4j.core.domain.WorkflowStatus;
import io.github.loopforge4j.core.engine.RunNotFoundException;
import io.github.loopforge4j.core.engine.RunStateException;
import io.github.loopforge4j.core.engine.WorkflowValidationException;
import io.github.loopforge4j.core.engine.WorkflowValidator;
import io.github.loopforge4j.core.preview.PreviewBuild;
import io.github.loopforge4j.core.store.CatalogStore;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Generic SOP templates (upstream {@code adoptGenericWorkflow}): shared SOPs marked {@code generic}
 * that are never run directly. Adopting one copies it into a project as an editable draft and
 * copies every role it uses into the same project, so the project can tune its roles without
 * changing the template or other projects. Re-adopting reuses the project's role copies, so local
 * edits are never overwritten.
 */
@Service
public class GenericTemplates {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final CatalogStore store;
    private final CatalogService catalog;

    public GenericTemplates(CatalogStore store, CatalogService catalog) {
        this.store = store;
        this.catalog = catalog;
    }

    /** The generic templates, by name. */
    public List<WorkflowDefinition> templates() {
        return store.workflows().stream()
                .filter(WorkflowDefinition::generic)
                .sorted(java.util.Comparator.comparing(WorkflowDefinition::name).thenComparing(WorkflowDefinition::id))
                .toList();
    }

    public WorkflowDefinition template(String id) {
        return store.workflow(id)
                .filter(WorkflowDefinition::generic)
                .orElseThrow(() -> new RunNotFoundException("generic template '" + id + "' not found"));
    }

    /** The roles a template's nodes use, in node order (system steps excluded). */
    public List<String> roles(WorkflowDefinition template) {
        List<String> ids = new ArrayList<>();
        for (WorkflowNode node : template.nodes()) {
            if (node.agentId() != null
                    && !node.agentId().isBlank()
                    && !PreviewBuild.isBuildNode(node.id())
                    && !ids.contains(node.agentId())) {
                ids.add(node.agentId());
            }
        }
        return ids;
    }

    /** The id an adopted copy gets unless the caller names one. */
    public static String defaultId(String templateId, String projectId) {
        String id = templateId + "-" + projectId;
        return id.length() <= 200 ? id : id.substring(0, 200);
    }

    /**
     * Copies {@code template} into {@code projectId} as draft {@code id} owned by {@code owner}.
     *
     * @throws RunStateException when {@code id} is taken
     */
    public WorkflowDefinition adopt(
            WorkflowDefinition template,
            String projectId,
            String id,
            String name,
            String owner,
            String sandboxProfileId) {
        if (!WorkflowValidator.ID.matcher(id).matches()) {
            throw new WorkflowValidationException(List.of("SOP id '" + id + "' is invalid"));
        }
        if (store.workflow(id).isPresent()) {
            throw new RunStateException("SOP '" + id + "' already exists");
        }
        Map<String, String> remap = new LinkedHashMap<>();
        for (String roleId : roles(template)) {
            AgentDefinition source = store.agent(roleId)
                    .filter(a -> a.projectId() == null)
                    .orElseThrow(() ->
                            new WorkflowValidationException(List.of("模板使用的角色 " + roleId + " 不存在或不是共享角色，请联系管理员修复模板")));
            remap.put(roleId, projectCopy(source, projectId).id());
        }
        List<WorkflowNode> nodes = template.nodes().stream()
                .map(n -> remap.containsKey(n.agentId()) ? n.withAgentId(remap.get(n.agentId())) : n)
                .toList();
        List<String> tags = new ArrayList<>(template.tags());
        tags.removeIf(t -> t.startsWith(WorkflowDefinition.GENERIC_SOURCE_TAG));
        tags.add(WorkflowDefinition.GENERIC_SOURCE_TAG + template.id());
        WorkflowDefinition adopted = new WorkflowDefinition(
                id,
                name == null || name.isBlank() ? template.name() : name.strip(),
                1,
                0,
                WorkflowStatus.DRAFT,
                projectId,
                owner,
                tags,
                template.description(),
                sandboxProfileId,
                nodes,
                template.edges(),
                null,
                null,
                template.preview(),
                SopLanes.infer(template),
                false);
        // the role copies are kept if this fails: they are reused by the next attempt
        return catalog.saveWorkflow(adopted);
    }

    /** The project's copy of a shared template role, created on first adoption. */
    private AgentDefinition projectCopy(AgentDefinition source, String projectId) {
        String candidate = source.id() + "-" + projectId;
        if (candidate.length() > 190) {
            candidate = candidate.substring(0, 190);
        }
        var existing = store.agent(candidate);
        if (existing.isPresent() && projectId.equals(existing.get().projectId())) {
            return existing.get();
        }
        String id = candidate;
        while (store.agent(id).isPresent()) {
            byte[] suffix = new byte[3];
            RANDOM.nextBytes(suffix);
            id = candidate + "-" + HexFormat.of().formatHex(suffix);
        }
        return catalog.saveAgent(source.copyFor(id, projectId, source.mcpServers()));
    }
}
