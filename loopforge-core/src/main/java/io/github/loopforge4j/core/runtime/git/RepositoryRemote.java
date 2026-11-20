package io.github.loopforge4j.core.runtime.git;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Host and project path of a Git remote URL: {@code https://[user@]host[:port]/group/repo(.git)},
 * {@code ssh://[user@]host[:port]/group/repo(.git)} or {@code user@host:group/repo(.git)}.
 *
 * @param host lower-case host name without port
 * @param path project path without leading slash and {@code .git} suffix, e.g. {@code group/sub/repo}
 */
public record RepositoryRemote(String host, String path) {

    private static final Pattern URL =
            Pattern.compile("^(?:https?|ssh)://(?:[^@/]+@)?([^/:]+)(?::\\d+)?/(.+?)(?:\\.git)?/?$");
    private static final Pattern SCP = Pattern.compile("^[A-Za-z0-9._-]+@([^:/]+):(.+?)(?:\\.git)?/?$");

    public static Optional<RepositoryRemote> parse(String url) {
        if (url == null) {
            return Optional.empty();
        }
        String trimmed = url.strip();
        Matcher m = URL.matcher(trimmed);
        if (!m.matches()) {
            m = SCP.matcher(trimmed);
            if (!m.matches()) {
                return Optional.empty();
            }
        }
        String path = m.group(2).replaceAll("^/+", "");
        if (path.isEmpty() || path.contains("..")) {
            return Optional.empty();
        }
        return Optional.of(new RepositoryRemote(m.group(1).toLowerCase(Locale.ROOT), path));
    }
}
