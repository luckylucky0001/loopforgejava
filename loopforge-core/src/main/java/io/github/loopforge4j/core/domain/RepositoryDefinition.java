package io.github.loopforge4j.core.domain;

import java.time.Instant;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A repository registered in the catalog. Runs freeze a {@link RepositoryContext} copy so later edits
 * never change an in-flight checkout.
 *
 * @param cloneUrl HTTPS, SSH or file URL used for clone and push
 * @param githubRepo {@code owner/name} for PR delivery; derived from a github.com URL when empty
 */
public record RepositoryDefinition(
        String id,
        String name,
        RepositoryProvider provider,
        String cloneUrl,
        String webUrl,
        String defaultBranch,
        String githubRepo,
        String projectId,
        Instant updatedAt) {

    private static final Pattern GITHUB_URL =
            Pattern.compile("^(?:https://(?:[^@/]+@)?github\\.com/|git@github\\.com:|ssh://git@github\\.com/)"
                    + "([A-Za-z0-9_.-]+)/([A-Za-z0-9_.-]+?)(?:\\.git)?/?$");

    public RepositoryDefinition {
        id = id == null ? "" : id.strip();
        name = name == null || name.isBlank() ? id : name.strip();
        cloneUrl = cloneUrl == null ? "" : cloneUrl.strip();
        defaultBranch = defaultBranch == null || defaultBranch.isBlank() ? "main" : defaultBranch.strip();
        if (githubRepo == null || githubRepo.isBlank()) {
            githubRepo = githubRepoOf(cloneUrl);
        }
        if (provider == null) {
            provider = inferProvider(cloneUrl, githubRepo);
        }
    }

    public RepositoryDefinition withUpdatedAt(Instant instant) {
        return new RepositoryDefinition(
                id, name, provider, cloneUrl, webUrl, defaultBranch, githubRepo, projectId, instant);
    }

    /** {@code owner/name} of a github.com clone URL, or {@code null}. */
    public static String githubRepoOf(String url) {
        if (url == null) {
            return null;
        }
        Matcher m = GITHUB_URL.matcher(url.strip());
        return m.matches() ? m.group(1) + "/" + m.group(2) : null;
    }

    static RepositoryProvider inferProvider(String url, String githubRepo) {
        String lower = url == null ? "" : url.toLowerCase(Locale.ROOT);
        if (githubRepo != null) {
            return RepositoryProvider.GITHUB;
        }
        if (lower.startsWith("https://") || lower.startsWith("http://")) {
            return RepositoryProvider.HTTPS;
        }
        if (lower.startsWith("ssh://") || lower.matches("^[a-z0-9._-]+@[^:]+:.*")) {
            return RepositoryProvider.SSH;
        }
        return RepositoryProvider.LOCAL;
    }
}
