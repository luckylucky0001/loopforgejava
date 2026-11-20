package io.github.loopforge4j.server.feedback;

import io.github.loopforge4j.core.json.Json;
import io.github.loopforge4j.server.account.AccessPolicy;
import io.github.loopforge4j.server.account.CurrentUser;
import io.github.loopforge4j.server.account.UserStore;
import io.github.loopforge4j.server.web.ApiException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.Principal;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * The console's floating page feedback (upstream {@code feedbackHandlers}): people submit reports
 * with up to five images; administrators read all of them, everybody else their own. Images are
 * checked by content, never by the declared type or extension, and served so that a browser can
 * only show them as images.
 */
@Tag(name = "Page feedback")
@RestController
@RequestMapping(PageFeedbackController.PATH)
class PageFeedbackController {

    static final String PATH = "/api/v1/page-feedback";
    static final int MAX_IMAGES = 5;
    static final long MAX_IMAGE_BYTES = 5L * 1024 * 1024;
    /** Five images and the form around them. */
    static final long MAX_REQUEST_BYTES = 27L * 1024 * 1024;

    private static final int MAX_TITLE = 120;
    private static final int MAX_DESCRIPTION = 5000;

    private final PageFeedbackStore store;
    private final AccessPolicy access;
    private final UserStore users;

    PageFeedbackController(PageFeedbackStore store, AccessPolicy access, UserStore users) {
        this.store = store;
        this.access = access;
        this.users = users;
    }

    /** What the widget sends besides the images; it has no submitter fields on purpose. */
    record Payload(
            String title,
            String description,
            String url,
            String route,
            String view,
            String frameUrl,
            PageFeedback.Context context,
            PageFeedback.Element element) {}

    @Operation(summary = "Submit page feedback: multipart with a JSON 'payload' part and up to five 'images'")
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ResponseEntity<PageFeedback> submit(
            @RequestPart("payload") String payload,
            @RequestPart(value = "images", required = false) List<MultipartFile> images,
            Principal principal) {
        CurrentUser user = person(principal);
        Payload body;
        try {
            body = Json.read(payload, Payload.class);
        } catch (RuntimeException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "反馈内容格式不正确");
        }
        PageFeedback feedback = normalize(body, user);
        List<PageFeedbackStore.NewImage> stored = images(images == null ? List.of() : images);
        return ResponseEntity.status(HttpStatus.CREATED).body(store.create(feedback, stored));
    }

    @Operation(summary = "Page feedback, newest first: all of it for administrators, your own otherwise")
    @GetMapping
    Map<String, Object> list(
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(defaultValue = "0") int offset,
            Principal principal) {
        CurrentUser user = person(principal);
        String owner = user.admin() ? null : user.id();
        int size = Math.max(1, Math.min(100, limit));
        int from = Math.max(0, offset);
        List<PageFeedback> items = store.list(owner, size, from);
        long total = store.count(owner);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", items);
        out.put("total", total);
        out.put("offset", from);
        out.put("limit", size);
        out.put("hasMore", from + items.size() < total);
        return out;
    }

    @GetMapping("/{id}")
    PageFeedback detail(@PathVariable String id, Principal principal) {
        return visible(id, person(principal));
    }

    @GetMapping("/{id}/images/{imageId}")
    ResponseEntity<byte[]> image(@PathVariable String id, @PathVariable String imageId, Principal principal) {
        visible(id, person(principal));
        PageFeedbackStore.StoredImage image = store.image(id, imageId)
                .filter(i -> IMAGE_TYPES.contains(i.mimeType()))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "图片不存在"));
        ContentDisposition disposition =
                image.fileName() == null || image.fileName().isBlank()
                        ? ContentDisposition.inline().build()
                        : ContentDisposition.inline()
                                .filename(image.fileName(), java.nio.charset.StandardCharsets.UTF_8)
                                .build();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(image.mimeType()))
                .header("X-Content-Type-Options", "nosniff")
                .header(
                        "Content-Security-Policy",
                        "default-src 'none'; img-src 'self'; style-src 'unsafe-inline'; sandbox")
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .header(HttpHeaders.CACHE_CONTROL, "private, max-age=86400")
                .body(image.data());
    }

    private PageFeedback visible(String id, CurrentUser user) {
        return store.find(id)
                .filter(f -> user.admin()
                        || user.id() != null && user.id().equals(f.submitter().userId()))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "反馈不存在"));
    }

    /** A signed-in person: not the service token, not an agent. */
    private CurrentUser person(Principal principal) {
        CurrentUser user = access.requireHuman(principal, "page feedback");
        if (user.service() || user.id() == null) {
            throw new AccessPolicy.AccessDeniedException("page feedback needs a personal account");
        }
        return user;
    }

    private PageFeedback normalize(Payload p, CurrentUser user) {
        String title = p.title() == null ? "" : p.title().strip();
        if (title.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "请填写反馈内容");
        }
        if (FeedbackText.length(title) > MAX_TITLE) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "标题不能超过 " + MAX_TITLE + " 字");
        }
        String description = p.description() == null ? "" : p.description().strip();
        if (FeedbackText.length(description) > MAX_DESCRIPTION) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "反馈内容不能超过 " + MAX_DESCRIPTION + " 字");
        }
        PageFeedback.Context c =
                p.context() == null ? new PageFeedback.Context(null, null, 0, null, null, null, null) : p.context();
        PageFeedback.Context context = new PageFeedback.Context(
                c.scroll(),
                c.viewport(),
                c.dpr(),
                FeedbackText.clip(FeedbackText.singleLine(c.userAgent()), 512),
                FeedbackText.clip(FeedbackText.singleLine(c.language()), 32),
                FeedbackText.clip(FeedbackText.singleLine(c.theme()), 16),
                FeedbackText.clip(FeedbackText.singleLine(c.capturedAt()), 40));
        PageFeedback.Element element = null;
        if (p.element() != null
                && p.element().selector() != null
                && !p.element().selector().isBlank()) {
            PageFeedback.Element e = p.element();
            element = new PageFeedback.Element(
                    FeedbackText.clip(e.selector().strip(), 1024),
                    FeedbackText.clip(FeedbackText.singleLine(e.tag()), 32),
                    FeedbackText.clip(
                            String.join(
                                    " ",
                                    (e.text() == null ? "" : e.text()).strip().split("\\s+")),
                            200),
                    e.rect(),
                    e.pageRect(),
                    e.point(),
                    e.inFrame());
        }
        String display = users.byId(user.id())
                .map(u -> u.displayName() == null || u.displayName().isBlank() ? u.username() : u.displayName())
                .orElse(user.username());
        return new PageFeedback(
                null,
                title,
                description,
                FeedbackText.clip(FeedbackText.url(p.url()), 2048),
                FeedbackText.clip(FeedbackText.route(p.route()), 512),
                FeedbackText.clip(FeedbackText.singleLine(p.view()), 128),
                FeedbackText.clip(FeedbackText.url(p.frameUrl()), 2048),
                context,
                element,
                new PageFeedback.Submitter(user.id(), user.username(), display),
                List.of(),
                null);
    }

    private static final java.util.Set<String> IMAGE_TYPES =
            java.util.Set.of("image/png", "image/jpeg", "image/gif", "image/webp");

    private static List<PageFeedbackStore.NewImage> images(List<MultipartFile> files) {
        List<MultipartFile> present =
                files.stream().filter(java.util.Objects::nonNull).toList();
        if (present.size() > MAX_IMAGES) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "最多上传 " + MAX_IMAGES + " 张图片");
        }
        List<PageFeedbackStore.NewImage> out = new ArrayList<>();
        for (MultipartFile file : present) {
            String name = file.getOriginalFilename() == null
                    ? ""
                    : file.getOriginalFilename().replace('\\', '/');
            name = FeedbackText.clip(FeedbackText.singleLine(name.substring(name.lastIndexOf('/') + 1)), 255);
            String label = name.isEmpty() ? "图片" : name;
            if (file.getSize() > MAX_IMAGE_BYTES) {
                throw new ApiException(HttpStatus.BAD_REQUEST, label + " 超过 5MB");
            }
            byte[] data;
            try {
                data = file.getBytes();
            } catch (IOException e) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "读取图片失败");
            }
            if (data.length == 0) {
                throw new ApiException(HttpStatus.BAD_REQUEST, label + " 是空文件");
            }
            if (data.length > MAX_IMAGE_BYTES) {
                throw new ApiException(HttpStatus.BAD_REQUEST, label + " 超过 5MB");
            }
            String type = sniff(data);
            if (type == null) {
                throw new ApiException(HttpStatus.BAD_REQUEST, label + " 不是支持的图片格式（仅支持 PNG / JPEG / GIF / WebP）");
            }
            out.add(new PageFeedbackStore.NewImage(name, type, data, sha256(data)));
        }
        return out;
    }

    /** The image type by its leading bytes. */
    static String sniff(byte[] d) {
        if (d.length >= 8
                && (d[0] & 0xff) == 0x89
                && d[1] == 'P'
                && d[2] == 'N'
                && d[3] == 'G'
                && d[4] == 0x0d
                && d[5] == 0x0a
                && d[6] == 0x1a
                && d[7] == 0x0a) {
            return "image/png";
        }
        if (d.length >= 3 && (d[0] & 0xff) == 0xff && (d[1] & 0xff) == 0xd8 && (d[2] & 0xff) == 0xff) {
            return "image/jpeg";
        }
        if (d.length >= 6
                && d[0] == 'G'
                && d[1] == 'I'
                && d[2] == 'F'
                && d[3] == '8'
                && (d[4] == '7' || d[4] == '9')
                && d[5] == 'a') {
            return "image/gif";
        }
        if (d.length >= 12
                && d[0] == 'R'
                && d[1] == 'I'
                && d[2] == 'F'
                && d[3] == 'F'
                && d[8] == 'W'
                && d[9] == 'E'
                && d[10] == 'B'
                && d[11] == 'P') {
            return "image/webp";
        }
        return null;
    }

    private static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
