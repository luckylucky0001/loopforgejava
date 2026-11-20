package io.github.loopforge4j.server.knowledge;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Connection to the upstream knowledge base MCP and which of its sources each project may read.
 *
 * @param token upstream credential; never returned by the API (see {@link #publicView})
 * @param writeEnabled reviewed submissions may be written back upstream; agents never get the write
 *     tool regardless
 * @param defaultSources sources of projects without their own entry; empty means every source the
 *     upstream token can see
 */
public record KnowledgeSettings(
        boolean enabled,
        String url,
        String token,
        boolean tokenConfigured,
        boolean writeEnabled,
        List<String> defaultSources,
        Map<String, List<String>> projectSources,
        String updatedBy,
        Instant updatedAt) {

    public static final KnowledgeSettings EMPTY =
            new KnowledgeSettings(false, null, null, false, false, List.of(), Map.of(), null, null);

    public KnowledgeSettings {
        url = url == null ? null : url.strip();
        token = token == null || token.isBlank() ? null : token.strip();
        defaultSources = clean(defaultSources);
        Map<String, List<String>> sources = new TreeMap<>();
        if (projectSources != null) {
            projectSources.forEach((project, list) -> {
                if (project != null && !project.isBlank()) {
                    sources.put(project.strip(), clean(list));
                }
            });
        }
        projectSources = java.util.Collections.unmodifiableMap(sources);
    }

    /** Sources a project may read; empty means unrestricted. */
    public List<String> sourcesFor(String projectId) {
        if (projectId != null && projectSources.containsKey(projectId)) {
            return projectSources.get(projectId);
        }
        return defaultSources;
    }

    /** What the API shows: everything but the token. */
    public KnowledgeSettings publicView(boolean tokenPresent) {
        return new KnowledgeSettings(
                enabled, url, null, tokenPresent, writeEnabled, defaultSources, projectSources, updatedBy, updatedAt);
    }

    static List<String> clean(List<String> in) {
        if (in == null) {
            return List.of();
        }
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String s : in) {
            if (s != null && !s.isBlank()) {
                out.add(s.strip());
            }
        }
        return List.copyOf(new ArrayList<>(out));
    }
}
