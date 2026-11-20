package io.github.loopforge4j.server.knowledge;

import java.time.Instant;
import java.util.Set;

/**
 * Distilled knowledge waiting for review before it is written to the knowledge base (upstream
 * {@code KnowledgeSubmission}).
 *
 * @param source target knowledge base source; must be inside the project's scope to publish
 * @param path target Markdown path inside the source
 * @param commit upstream commit of the published document
 */
public record KnowledgeSubmission(
        String id,
        String projectId,
        String runId,
        String nodeId,
        String artifactId,
        String workflowId,
        String title,
        String source,
        String path,
        String content,
        String status,
        String commit,
        String error,
        String reviewNote,
        String createdBy,
        String reviewedBy,
        Instant createdAt,
        Instant updatedAt,
        Instant publishedAt) {

    public static final String PENDING = "pending";
    public static final String REJECTED = "rejected";
    public static final String PUBLISHED = "published";
    public static final Set<String> STATUSES = Set.of(PENDING, REJECTED, PUBLISHED);

    public KnowledgeSubmission withReview(
            String newTitle,
            String newSource,
            String newPath,
            String newContent,
            String newStatus,
            String newCommit,
            String newError,
            String newNote,
            String reviewer,
            Instant published) {
        return new KnowledgeSubmission(
                id,
                projectId,
                runId,
                nodeId,
                artifactId,
                workflowId,
                newTitle,
                newSource,
                newPath,
                newContent,
                newStatus,
                newCommit,
                newError,
                newNote,
                createdBy,
                reviewer,
                createdAt,
                updatedAt,
                published);
    }
}
