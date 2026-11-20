package io.github.loopforge4j.server.tracker;

import java.net.URI;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds one TAPD story, bug or task in a pasted URL or "title + link" text (upstream {@code
 * ParseTAPDStoryURL}): detail pages, list pages with a preview dialog, the new and old layouts.
 */
public final class TapdLinks {

    /** A TAPD record named by a link. */
    public record Ref(String workspaceId, String id, String kind) {}

    private static final Pattern URL = Pattern.compile(
            "(?i)(?:[a-z][a-z0-9+.-]*://|//|(?:www\\.)?tapd\\.cn/)[^\\s\\p{Z}<>\\[\\](){}\"'`，。；：！？、（）【】《》「」『』]+");
    private static final Pattern WORKSPACE = Pattern.compile("^/(?:tapd_fe/)?(\\d{2,12})(?:/|$)");
    private static final Pattern DETAIL =
            Pattern.compile("/(story/detail|stories/view|bug/detail|bugs/view|task/detail|tasks/view)/(\\d+)/?$");
    private static final Pattern PREVIEW = Pattern.compile("^(story|bug|task)_(\\d+)$");

    private TapdLinks() {}

    /**
     * @param extraHosts further TAPD hosts (a private deployment)
     * @throws IllegalArgumentException with a message for the person who pasted it
     */
    public static Ref parse(String raw, Set<String> extraHosts) {
        String text = raw == null ? "" : raw.strip();
        if (text.isEmpty()) {
            throw new IllegalArgumentException("请粘贴 TAPD 需求链接");
        }
        Ref found = null;
        String firstError = null;
        Matcher m = URL.matcher(text);
        while (m.find()) {
            String candidate = m.group();
            // a bare host must stand alone, not be the tail of another host or an e-mail address
            if (!candidate.contains("://")
                    && !candidate.startsWith("//")
                    && m.start() > 0
                    && "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_-.@/"
                                    .indexOf(text.charAt(m.start() - 1))
                            >= 0) {
                continue;
            }
            candidate = candidate.replaceAll("[.,;:!?]+$", "");
            try {
                Ref ref = link(candidate, extraHosts);
                if (found != null && !found.equals(ref)) {
                    throw new IllegalArgumentException("检测到多个不同的 TAPD 需求、缺陷或任务链接，请每次只导入一个");
                }
                found = ref;
            } catch (IllegalArgumentException e) {
                if (e.getMessage().startsWith("检测到多个")) {
                    throw e;
                }
                if (firstError == null) {
                    firstError = e.getMessage();
                }
            }
        }
        if (found != null) {
            return found;
        }
        throw new IllegalArgumentException(firstError != null ? firstError : "无法识别 TAPD 链接，请粘贴需求详情链接（可包含标题）");
    }

    private static Ref link(String raw, Set<String> extraHosts) {
        String url = raw.startsWith("//") ? "https:" + raw : raw.contains("://") ? raw : "https://" + raw;
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("无法识别 TAPD 链接");
        }
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        boolean tapd = host.equals("www.tapd.cn") || host.equals("tapd.cn") || extraHosts.contains(host);
        if (!("https".equals(uri.getScheme()) || "http".equals(uri.getScheme())) || !tapd) {
            throw new IllegalArgumentException("请粘贴 tapd.cn 的需求详情链接");
        }
        String path = uri.getPath() == null ? "" : uri.getPath();
        String workspace = null;
        String id = null;
        String kind = "story";
        Matcher w = WORKSPACE.matcher(path);
        if (w.find()) {
            workspace = w.group(1);
        }
        String query = uri.getRawQuery() == null ? "" : uri.getRawQuery();
        for (String pair : query.split("&")) {
            if (pair.startsWith("dialog_preview_id=")) {
                Matcher p = PREVIEW.matcher(
                        java.net.URLDecoder.decode(pair.substring(18), java.nio.charset.StandardCharsets.UTF_8));
                if (p.matches()) {
                    kind = p.group(1);
                    id = p.group(2);
                }
            }
        }
        if (id == null) {
            Matcher d = DETAIL.matcher(path);
            if (d.find()) {
                id = d.group(2);
                kind = d.group(1).startsWith("bug") ? "bug" : d.group(1).startsWith("task") ? "task" : "story";
            }
        }
        if (workspace == null || id == null || workspace.equals(id)) {
            throw new IllegalArgumentException("链接里找不到 TAPD 项目和需求 ID，请使用需求详情页地址");
        }
        return new Ref(workspace, id, kind);
    }
}
