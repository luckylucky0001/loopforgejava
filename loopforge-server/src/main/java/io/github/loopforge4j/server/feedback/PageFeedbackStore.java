package io.github.loopforge4j.server.feedback;

import io.github.loopforge4j.core.json.Json;
import io.github.loopforge4j.server.persistence.SqlTime;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

/** Page feedback and its images (upstream {@code page_feedbacks}, {@code page_feedback_images}). */
@Repository
public class PageFeedbackStore {

    /** An image to store: what the server sniffed and hashed, never what the client claimed. */
    public record NewImage(String fileName, String mimeType, byte[] data, String sha256) {}

    /** A stored image with its bytes. */
    public record StoredImage(String feedbackId, String id, String fileName, String mimeType, byte[] data) {}

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final Clock clock;

    public PageFeedbackStore(JdbcClient jdbc, TransactionTemplate tx, Clock clock) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.clock = clock;
    }

    public PageFeedback create(PageFeedback feedback, List<NewImage> images) {
        String id = "fb_" + UUID.randomUUID().toString().replace("-", "");
        Instant now = clock.instant();
        tx.executeWithoutResult(status -> {
            jdbc.sql("INSERT INTO page_feedbacks (id, title, description, url, route, page_view, frame_url, context,"
                            + " element, user_id, username, display_name, created_at) VALUES (:id, :title, :description,"
                            + " :url, :route, :view, :frame, :context, :element, :user, :username, :display, :now)")
                    .param("id", id)
                    .param("title", feedback.title())
                    .param("description", feedback.description())
                    .param("url", feedback.url())
                    .param("route", feedback.route())
                    .param("view", feedback.view())
                    .param("frame", feedback.frameUrl())
                    .param("context", Json.write(feedback.context()))
                    .param("element", feedback.element() == null ? null : Json.write(feedback.element()))
                    .param("user", feedback.submitter().userId())
                    .param("username", feedback.submitter().username())
                    .param("display", feedback.submitter().displayName())
                    .param("now", SqlTime.of(now))
                    .update();
            int position = 0;
            for (NewImage image : images) {
                jdbc.sql(
                                "INSERT INTO page_feedback_images (id, feedback_id, seq, file_name, mime_type, byte_size,"
                                        + " sha256, data, created_at) VALUES (:id, :fb, :pos, :name, :mime, :size, :sha, :data, :now)")
                        .param("id", "img_" + UUID.randomUUID().toString().replace("-", ""))
                        .param("fb", id)
                        .param("pos", position++)
                        .param("name", image.fileName())
                        .param("mime", image.mimeType())
                        .param("size", image.data().length)
                        .param("sha", image.sha256())
                        .param("data", image.data())
                        .param("now", SqlTime.of(now))
                        .update();
            }
        });
        return find(id).orElseThrow();
    }

    /** Newest first; {@code userId} limits the page to one submitter ({@code null}: everybody). */
    public List<PageFeedback> list(String userId, int limit, int offset) {
        String where = userId == null ? "" : " WHERE user_id = :user";
        var spec = jdbc.sql("SELECT * FROM page_feedbacks" + where + " ORDER BY created_at DESC, id DESC"
                        + " LIMIT :limit OFFSET :offset")
                .param("limit", limit)
                .param("offset", offset);
        if (userId != null) {
            spec = spec.param("user", userId);
        }
        List<PageFeedback> rows = spec.query((rs, i) -> row(rs)).list();
        Map<String, List<PageFeedback.Image>> images =
                images(rows.stream().map(PageFeedback::id).toList());
        List<PageFeedback> out = new ArrayList<>();
        for (PageFeedback f : rows) {
            out.add(withImages(f, images.getOrDefault(f.id(), List.of())));
        }
        return out;
    }

    /** Reports {@code userId} filed since {@code since}. */
    public long countSince(String userId, Instant since) {
        return jdbc.sql("SELECT COUNT(*) FROM page_feedbacks WHERE user_id = :user AND created_at >= :since")
                .param("user", userId)
                .param("since", SqlTime.of(since))
                .query(Long.class)
                .single();
    }

    public long count(String userId) {
        var spec = jdbc.sql("SELECT COUNT(*) FROM page_feedbacks" + (userId == null ? "" : " WHERE user_id = :user"));
        if (userId != null) {
            spec = spec.param("user", userId);
        }
        return spec.query(Long.class).single();
    }

    public Optional<PageFeedback> find(String id) {
        return jdbc.sql("SELECT * FROM page_feedbacks WHERE id = :id")
                .param("id", id)
                .query((rs, i) -> row(rs))
                .optional()
                .map(f -> withImages(f, images(List.of(f.id())).getOrDefault(f.id(), List.of())));
    }

    public Optional<StoredImage> image(String feedbackId, String imageId) {
        return jdbc.sql("SELECT feedback_id, id, file_name, mime_type, data FROM page_feedback_images"
                        + " WHERE feedback_id = :fb AND id = :id")
                .param("fb", feedbackId)
                .param("id", imageId)
                .query((rs, i) -> new StoredImage(
                        rs.getString("feedback_id"),
                        rs.getString("id"),
                        rs.getString("file_name"),
                        rs.getString("mime_type"),
                        rs.getBytes("data")))
                .optional();
    }

    private Map<String, List<PageFeedback.Image>> images(List<String> ids) {
        Map<String, List<PageFeedback.Image>> out = new LinkedHashMap<>();
        if (ids.isEmpty()) {
            return out;
        }
        jdbc.sql("SELECT id, feedback_id, seq, file_name, mime_type, byte_size, sha256 FROM page_feedback_images"
                        + " WHERE feedback_id IN (:ids) ORDER BY feedback_id, seq")
                .param("ids", ids)
                .query((rs, i) -> {
                    String fb = rs.getString("feedback_id");
                    String image = rs.getString("id");
                    out.computeIfAbsent(fb, k -> new ArrayList<>())
                            .add(new PageFeedback.Image(
                                    image,
                                    rs.getInt("seq"),
                                    rs.getString("file_name"),
                                    rs.getString("mime_type"),
                                    rs.getLong("byte_size"),
                                    rs.getString("sha256"),
                                    "/api/v1/page-feedback/" + fb + "/images/" + image));
                    return null;
                })
                .list();
        return out;
    }

    private static PageFeedback withImages(PageFeedback f, List<PageFeedback.Image> images) {
        return new PageFeedback(
                f.id(),
                f.title(),
                f.description(),
                f.url(),
                f.route(),
                f.view(),
                f.frameUrl(),
                f.context(),
                f.element(),
                f.submitter(),
                images,
                f.createdAt());
    }

    private static PageFeedback row(java.sql.ResultSet rs) throws java.sql.SQLException {
        String element = rs.getString("element");
        return new PageFeedback(
                rs.getString("id"),
                rs.getString("title"),
                rs.getString("description"),
                rs.getString("url"),
                rs.getString("route"),
                rs.getString("page_view"),
                rs.getString("frame_url"),
                Json.read(rs.getString("context"), PageFeedback.Context.class),
                element == null ? null : Json.read(element, PageFeedback.Element.class),
                new PageFeedback.Submitter(
                        rs.getString("user_id"), rs.getString("username"), rs.getString("display_name")),
                List.of(),
                SqlTime.read(rs, "created_at"));
    }
}
