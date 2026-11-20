package io.github.loopforge4j.server.preview;

import io.github.loopforge4j.core.json.Json;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Makes a static site built for "/" work under {@code /preview/<token>/} (port of upstream
 * {@code rewritePreviewHTML} and friends): a base tag, root-relative references and asset URLs moved
 * under the prefix, the history shim for client-side routers and the backend interceptor.
 */
final class PreviewRewriter {

    static final int MAX_REWRITTEN = 2 << 20;

    private static final Pattern ROOT_ATTR =
            Pattern.compile("(?i)(\\s(?:src|href|poster|action)\\s*=\\s*(['\"]))(/[^/][^'\"]*)");
    private static final Pattern JS_ROOT_ASSET =
            Pattern.compile("([`'\"])(/(?:assets|static|chunks|media|fonts|css|js|img|images)(?:/[^`'\"]*)?)");
    private static final Pattern REFERRER_META =
            Pattern.compile("(?i)<meta\\b[^>]*?\\sname\\s*=\\s*(?:\"referrer\"|'referrer'|referrer\\b)[^>]*>");

    static final String BACKEND_JS = resource("backend-intercept.js");
    static final String HISTORY_JS = resource("history-shim.js");
    static final String STORAGE_JS = resource("storage-shim.js");

    private PreviewRewriter() {}

    private static String resource(String name) {
        try (InputStream in = PreviewRewriter.class.getResourceAsStream("/preview/" + name)) {
            if (in == null) {
                throw new IllegalStateException("missing preview resource " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * @param prefix {@code /preview/<token>} without a trailing slash
     * @param backend the backend address calls go to, or null
     * @param routed whether the site uses the History API
     * @param emulateStorage the site runs in an opaque origin without browser storage
     */
    static String html(String html, String prefix, String backend, boolean routed, boolean emulateStorage) {
        if (html.length() > MAX_REWRITTEN) {
            return html;
        }
        String out = baseTag(html, prefix);
        out = REFERRER_META
                .matcher(out)
                .replaceAll(Matcher.quoteReplacement("<meta name=\"referrer\" content=\"same-origin\">"));
        out = viewport(out);
        out = backend(out, prefix, backend);
        out = history(out, prefix, routed);
        if (emulateStorage) {
            out = storage(out);
        }
        out = rootRelative(out, prefix);
        return css(out, prefix);
    }

    static String baseTag(String html, String prefix) {
        if (html.toLowerCase(Locale.ROOT).contains("<base")) {
            return html;
        }
        String href = prefix.isEmpty() ? "/" : prefix.endsWith("/") ? prefix : prefix + "/";
        String tag = "<base href=\"" + href + "\">";
        String out = afterHeadOpen(html, tag);
        return out != null ? out : tag + html;
    }

    static String viewport(String html) {
        String tag = "<style data-lf-preview-viewport>html,body{height:100%;min-height:100%;margin:0}</style>";
        if (html.contains("data-lf-preview-viewport")) {
            return html;
        }
        String out = beforeHeadClose(html, tag);
        return out != null ? out : tag + html;
    }

    static String backend(String html, String prefix, String backend) {
        if (backend == null
                || backend.isBlank()
                || html.contains("data-lf-preview-backend")
                || BACKEND_JS.contains("</script>")) {
            return html;
        }
        // a classic script at the start of <head> runs before deferred module bundles
        String script = BACKEND_JS.replace("__LF_PREFIX__", Json.write(trimTrailingSlash(prefix)));
        String tag = "<script data-lf-preview-backend>window.__LOOPFORGE_PREVIEW_BACKEND__=" + scriptJson(backend) + ";"
                + script + "</script>";
        String out = afterHeadOpen(html, tag);
        return out != null ? out : tag + html;
    }

    static String history(String html, String prefix, boolean routed) {
        String root = trimTrailingSlash(prefix);
        if (root.isEmpty() || html.contains("data-lf-preview-history") || HISTORY_JS.contains("</script>")) {
            return html;
        }
        // at the end of <head>, so any <base> has resolved against the real preview URL
        String shim = HISTORY_JS
                .replace("__LF_PREFIX__", Json.write(root))
                .replace("__LF_ROUTED__", Boolean.toString(routed));
        String tag = "<script data-lf-preview-history>" + shim + "</script>";
        String out = beforeHeadClose(html, tag);
        return out != null ? out : tag + html;
    }

    static String storage(String html) {
        if (html.contains("data-lf-preview-storage")) {
            return html;
        }
        String tag = "<script data-lf-preview-storage>" + STORAGE_JS + "</script>";
        String out = afterHeadOpen(html, tag);
        return out != null ? out : tag + html;
    }

    static String rootRelative(String html, String prefix) {
        if (prefix.isEmpty()) {
            return html;
        }
        Matcher m = ROOT_ATTR.matcher(html);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String ref = m.group(3);
            String replacement =
                    ref.startsWith(prefix + "/") || ref.equals(prefix) ? m.group() : m.group(1) + prefix + ref;
            m.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(out);
        return out.toString();
    }

    /** Prefixes root-relative {@code url(/...)} references. */
    static String css(String s, String prefix) {
        StringBuilder b = new StringBuilder(s.length() + 64);
        int i = 0;
        while (true) {
            int j = s.indexOf("url(", i);
            if (j < 0) {
                b.append(s, i, s.length());
                break;
            }
            b.append(s, i, j + 4);
            int k = j + 4;
            while (k < s.length() && (s.charAt(k) == ' ' || s.charAt(k) == '\t')) {
                b.append(s.charAt(k));
                k++;
            }
            if (k < s.length() && (s.charAt(k) == '\'' || s.charAt(k) == '"')) {
                b.append(s.charAt(k));
                k++;
            }
            if (k < s.length() && s.charAt(k) == '/' && (k + 1 >= s.length() || s.charAt(k + 1) != '/')) {
                if (!s.startsWith(prefix, k)) {
                    b.append(prefix);
                }
            }
            i = k;
        }
        return b.toString();
    }

    /** Prefixes quoted root-relative asset paths in scripts; routes such as /api stay as they are. */
    static String js(String s, String prefix) {
        if (prefix.isEmpty() || s.length() > MAX_REWRITTEN) {
            return s;
        }
        Matcher m = JS_ROOT_ASSET.matcher(s);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String ref = m.group(2);
            String replacement =
                    ref.startsWith(prefix + "/") || ref.equals(prefix) ? m.group() : m.group(1) + prefix + ref;
            m.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(out);
        return out.toString();
    }

    private static String afterHeadOpen(String html, String tag) {
        String lower = html.toLowerCase(Locale.ROOT);
        int i = lower.indexOf("<head>");
        if (i >= 0) {
            int at = i + "<head>".length();
            return html.substring(0, at) + tag + html.substring(at);
        }
        i = lower.indexOf("<head ");
        if (i >= 0) {
            int end = html.indexOf('>', i);
            if (end >= 0) {
                return html.substring(0, end + 1) + tag + html.substring(end + 1);
            }
        }
        return null;
    }

    private static String beforeHeadClose(String html, String tag) {
        int i = html.toLowerCase(Locale.ROOT).indexOf("</head>");
        return i < 0 ? null : html.substring(0, i) + tag + html.substring(i);
    }

    private static String trimTrailingSlash(String value) {
        String out = value;
        while (out.endsWith("/")) {
            out = out.substring(0, out.length() - 1);
        }
        return out;
    }

    /** JSON for an inline script: "<" is escaped so a value can never close the script element. */
    private static String scriptJson(String value) {
        return Json.write(value).replace("<", "\\u003c");
    }
}
