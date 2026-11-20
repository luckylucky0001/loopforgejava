package io.github.loopforge4j.server.feedback;

import java.time.Instant;
import java.util.List;

/**
 * One report from the console's floating feedback widget (upstream {@code PageFeedback}). It belongs
 * to no project; the submitter is always copied from the session, never from the request.
 */
public record PageFeedback(
        String id,
        String title,
        String description,
        String url,
        String route,
        String view,
        String frameUrl,
        Context context,
        Element element,
        Submitter submitter,
        List<Image> images,
        Instant createdAt) {

    public PageFeedback {
        images = images == null ? List.of() : List.copyOf(images);
    }

    /** Who submitted it, frozen at submit time. */
    public record Submitter(String userId, String username, String displayName) {}

    /** The page environment captured when the card was opened. */
    public record Context(
            Point scroll,
            Size viewport,
            double dpr,
            String userAgent,
            String language,
            String theme,
            String capturedAt) {}

    /** The element the person picked on the page, if any. */
    public record Element(
            String selector, String tag, String text, Rect rect, Rect pageRect, Point point, boolean inFrame) {}

    public record Point(double x, double y) {}

    public record Size(double width, double height) {}

    public record Rect(double x, double y, double width, double height) {}

    /** An attached image; listings carry metadata and the download path only. */
    public record Image(
            String id, int position, String fileName, String mimeType, long size, String sha256, String url) {}
}
