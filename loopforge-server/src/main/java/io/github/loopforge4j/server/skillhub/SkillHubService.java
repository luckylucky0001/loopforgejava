package io.github.loopforge4j.server.skillhub;

import io.github.loopforge4j.core.domain.AssetKind;
import io.github.loopforge4j.core.domain.SkillDefinition;
import io.github.loopforge4j.core.store.CatalogStore;
import io.github.loopforge4j.server.account.AccessPolicy;
import io.github.loopforge4j.server.account.CurrentUser;
import io.github.loopforge4j.server.catalog.CatalogService;
import io.github.loopforge4j.server.config.LoopForgeProperties;
import io.github.loopforge4j.server.persistence.SqlTime;
import io.github.loopforge4j.server.web.ApiException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.Principal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

/**
 * The SkillHub skills market (upstream {@code skillhubHandlers}): browse the market and install a
 * version of a skill into the catalog (administrators, since skills are shared by all roles), later
 * update it to another version.
 */
@Service
public class SkillHubService {

    static final Pattern SLUG = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$");
    static final Pattern SKILL_ID = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$");
    static final Pattern VERSION = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._+-]{0,99}$");
    static final int MAX_PAGE_SIZE = 50;

    private final SkillHubClient client;
    private final CatalogStore catalog;
    private final CatalogService catalogService;
    private final AccessPolicy access;
    private final JdbcClient jdbc;
    private final LoopForgeProperties.Skillhub config;
    private final Clock clock;

    public SkillHubService(
            SkillHubClient client,
            CatalogStore catalog,
            CatalogService catalogService,
            AccessPolicy access,
            JdbcClient jdbc,
            LoopForgeProperties properties,
            Clock clock) {
        this.client = client;
        this.catalog = catalog;
        this.catalogService = catalogService;
        this.access = access;
        this.jdbc = jdbc;
        this.config = properties.skillhub();
        this.clock = clock;
    }

    /** Where an installed skill came from. */
    public record Source(
            String skillId,
            String namespace,
            String slug,
            String version,
            String installedBy,
            Instant installedAt,
            Instant updatedAt) {}

    /** The outcome of an install or update. */
    public record Installed(SkillDefinition skill, Source source, List<String> skipped) {}

    public Map<String, Object> status(Principal principal) {
        human(principal);
        return Map.of("configured", client.configured());
    }

    public JsonNode list(Map<String, String> query, Principal principal) {
        String user = human(principal);
        Map<String, String> q = new LinkedHashMap<>();
        for (String key : List.of("keyword", "category", "namespace", "sortBy", "page", "pageSize")) {
            String v = query.get(key);
            if (v != null && !v.isBlank()) {
                q.put(key, v.length() > 200 ? v.substring(0, 200) : v);
            }
        }
        if (q.containsKey("pageSize")) {
            try {
                q.put(
                        "pageSize",
                        String.valueOf(Math.max(1, Math.min(MAX_PAGE_SIZE, Integer.parseInt(q.get("pageSize"))))));
            } catch (NumberFormatException e) {
                q.remove("pageSize");
            }
        }
        JsonNode out = client.json("/api/skills", q, userKey(user));
        if (out.path("code").asInt(0) != 0) {
            throw new ApiException(
                    HttpStatus.BAD_GATEWAY,
                    "SkillHub 列表接口返回错误：" + out.path("message").asString(""));
        }
        return out.path("data");
    }

    public JsonNode categories(Principal principal) {
        return client.json("/api/v1/categories", Map.of(), userKey(human(principal)));
    }

    public JsonNode detail(String slug, String namespace, Principal principal) {
        return client.json(skillPath(slug, ""), scope(namespace, null), userKey(human(principal)));
    }

    public JsonNode versions(String slug, String namespace, Principal principal) {
        return client.json(skillPath(slug, "/versions"), scope(namespace, null), userKey(human(principal)));
    }

    public JsonNode files(String slug, String namespace, String version, Principal principal) {
        return client.json(skillPath(slug, "/files"), scope(namespace, version(version)), userKey(human(principal)));
    }

    /** One file of a skill version as text (binary files cannot be previewed). */
    public Map<String, Object> file(String slug, String namespace, String version, String path, Principal principal) {
        String user = human(principal);
        String clean =
                filePath(path).orElseThrow(() -> new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "invalid path"));
        Map<String, String> q = scope(namespace, version(version));
        q.put("path", clean);
        byte[] bytes = client.file(
                skillPath(slug, "/file"), q, userKey(user), config.maxFileSize().toBytes());
        if (bytes == null) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "文件超过平台限额");
        }
        Optional<String> text = utf8(bytes);
        return text.<Map<String, Object>>map(t -> Map.of("path", clean, "content", t))
                .orElseGet(() -> Map.of("path", clean, "binary", true, "size", bytes.length));
    }

    public record Install(String slug, String namespace, String version, String id, String name) {}

    public Installed install(Install body, Principal principal) {
        String user = admin(principal);
        String slug = slug(body.slug());
        String namespace = body.namespace() == null || body.namespace().isBlank() ? null : slug(body.namespace());
        String id = body.id() == null || body.id().isBlank() ? slug : body.id().strip();
        if (!SKILL_ID.matcher(id).matches()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "技能标识只能包含字母、数字、点、下划线和连字符，且以字母或数字开头（最长 64 位）");
        }
        if (catalog.asset(AssetKind.SKILL, id).isPresent()) {
            throw new ApiException(HttpStatus.CONFLICT, "技能标识「" + id + "」已被使用，请换一个");
        }
        // the market's namespace, also when the request left it out
        String resolved = namespace != null ? namespace : namespaceOf(slug, user);
        Optional<Source> already = sources().stream()
                .filter(s -> s.slug().equals(slug) && java.util.Objects.equals(s.namespace(), resolved))
                .filter(s -> catalog.asset(AssetKind.SKILL, s.skillId()).isPresent())
                .findFirst();
        if (already.isPresent()) {
            throw new ApiException(
                    HttpStatus.CONFLICT, "已安装该技能（标识 " + already.get().skillId() + "），如需新版本请使用更新");
        }
        return fetch(id, slug, resolved, version(body.version()), body.name(), null, user);
    }

    private String namespaceOf(String slug, String user) {
        JsonNode detail = client.json(skillPath(slug, ""), scope(null, null), userKey(user));
        return text(
                detail.path("namespace").path("handle"),
                text(detail.path("owner").path("handle"), null));
    }

    public record Update(String skillId, String version) {}

    public Installed update(Update body, Principal principal) {
        String user = admin(principal);
        Source source = sources().stream()
                .filter(s -> s.skillId().equals(body.skillId()))
                .findFirst()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "这个技能不是从 SkillHub 安装的"));
        SkillDefinition current = catalog.asset(AssetKind.SKILL, source.skillId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "skill not found"));
        return fetch(
                source.skillId(),
                source.slug(),
                source.namespace(),
                version(body.version()),
                current.name(),
                source,
                user);
    }

    /** Skills installed from the market (that still exist). */
    public List<Source> installed(Principal principal) {
        human(principal);
        return sources().stream()
                .filter(s -> catalog.asset(AssetKind.SKILL, s.skillId()).isPresent())
                .toList();
    }

    private Installed fetch(
            String id, String slug, String namespace, String version, String name, Source previous, String user) {
        String key = userKey(user);
        JsonNode detail = client.json(skillPath(slug, ""), scope(namespace, null), key);
        String ns = namespace != null
                ? namespace
                : text(
                        detail.path("namespace").path("handle"),
                        text(detail.path("owner").path("handle"), null));
        String chosen =
                version != null ? version : text(detail.path("latestVersion").path("version"), null);
        if (chosen == null) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "SkillHub 未返回可安装的版本");
        }
        JsonNode listing = client.json(skillPath(slug, "/files"), scope(ns, chosen), key);
        List<String> skipped = new ArrayList<>();
        Map<String, String> files = new LinkedHashMap<>();
        String skillMd = null;
        long total = 0;
        int count = 0;
        for (JsonNode entry : listing.path("files")) {
            String raw = text(entry.path("path"), null);
            Optional<String> path = filePath(raw);
            if (path.isEmpty()) {
                skipped.add(raw + "（路径无效）");
                continue;
            }
            if (++count > config.maxFiles()) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "技能包文件超过 " + config.maxFiles() + " 个");
            }
            if (entry.path("size").asLong(0) > config.maxFileSize().toBytes()) {
                skipped.add(path.get() + "（超过单文件上限）");
                continue;
            }
            Map<String, String> q = scope(ns, chosen);
            q.put("path", path.get());
            byte[] bytes = client.file(
                    skillPath(slug, "/file"), q, key, config.maxFileSize().toBytes());
            if (bytes == null) {
                skipped.add(path.get() + "（超过单文件上限）");
                continue;
            }
            total += bytes.length;
            if (total > config.maxPackageSize().toBytes()) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "技能包超过平台限额");
            }
            Optional<String> text = utf8(bytes);
            if (text.isEmpty()) {
                skipped.add(path.get() + "（二进制文件）");
                continue;
            }
            if (path.get().equals("SKILL.md")) {
                skillMd = text.get();
            } else {
                files.put(path.get(), text.get());
            }
        }
        if (skillMd == null) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "技能包里没有 SKILL.md");
        }
        String display = name != null && !name.isBlank()
                ? name.strip()
                : text(detail.path("skill").path("displayName"), slug);
        String description = text(
                detail.path("skill").path("summary_zh"),
                text(detail.path("skill").path("summary"), null));
        SkillDefinition skill = catalogService.saveAsset(
                AssetKind.SKILL,
                new SkillDefinition(
                        id,
                        display.length() > 100 ? display.substring(0, 100) : display,
                        description == null || description.length() <= 500
                                ? description
                                : description.substring(0, 500),
                        skillMd,
                        files,
                        null));
        Instant now = clock.instant();
        Source source = new Source(id, ns, slug, chosen, user, previous == null ? now : previous.installedAt(), now);
        if (previous == null) {
            // a skill deleted earlier under the same id may have left its record behind
            jdbc.sql("DELETE FROM skill_sources WHERE skill_id = :id")
                    .param("id", id)
                    .update();
            jdbc.sql(
                            "INSERT INTO skill_sources (skill_id, namespace, slug, version, installed_by, installed_at, updated_at)"
                                    + " VALUES (:id, :ns, :slug, :v, :by, :now, :now)")
                    .param("id", id)
                    .param("ns", ns)
                    .param("slug", slug)
                    .param("v", chosen)
                    .param("by", user)
                    .param("now", SqlTime.of(now))
                    .update();
        } else {
            jdbc.sql("UPDATE skill_sources SET version = :v, namespace = :ns, installed_by = :by, updated_at = :now"
                            + " WHERE skill_id = :id")
                    .param("id", id)
                    .param("ns", ns)
                    .param("v", chosen)
                    .param("by", user)
                    .param("now", SqlTime.of(now))
                    .update();
        }
        return new Installed(skill, source, skipped);
    }

    private List<Source> sources() {
        return jdbc.sql("SELECT skill_id, namespace, slug, version, installed_by, installed_at, updated_at"
                        + " FROM skill_sources ORDER BY skill_id")
                .query((rs, i) -> new Source(
                        rs.getString("skill_id"),
                        rs.getString("namespace"),
                        rs.getString("slug"),
                        rs.getString("version"),
                        rs.getString("installed_by"),
                        SqlTime.read(rs, "installed_at"),
                        SqlTime.read(rs, "updated_at")))
                .list();
    }

    /** A relative path inside the package: no traversal, no absolute paths, no control characters. */
    static Optional<String> filePath(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String p = raw.strip().replace('\\', '/');
        while (p.startsWith("./")) {
            p = p.substring(2);
        }
        if (p.isEmpty()
                || p.length() > 300
                || p.startsWith("/")
                || p.contains("//")
                || p.chars().anyMatch(c -> c < 0x20 || c == 0x7f || c == ':')
                || java.util.Arrays.stream(p.split("/")).anyMatch(s -> s.equals("..") || s.equals("."))) {
            return Optional.empty();
        }
        return Optional.of(p);
    }

    private static Optional<String> utf8(byte[] bytes) {
        try {
            String text = StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
            return text.indexOf('\0') >= 0 ? Optional.empty() : Optional.of(text);
        } catch (CharacterCodingException e) {
            return Optional.empty();
        }
    }

    private static String skillPath(String slug, String suffix) {
        return "/api/v1/skills/" + java.net.URLEncoder.encode(slug(slug), StandardCharsets.UTF_8) + suffix;
    }

    private static Map<String, String> scope(String namespace, String version) {
        Map<String, String> q = new LinkedHashMap<>();
        if (namespace != null && !namespace.isBlank()) {
            q.put("namespace", slug(namespace));
        }
        if (version != null) {
            q.put("version", version);
        }
        return q;
    }

    private static String slug(String value) {
        String s = value == null ? "" : value.strip();
        if (!SLUG.matcher(s).matches()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "无效的 SkillHub 技能标识");
        }
        return s;
    }

    private static String version(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String v = value.strip();
        if (!VERSION.matcher(v).matches()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "无效的版本");
        }
        return v;
    }

    private static String text(JsonNode node, String fallback) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return fallback;
        }
        String s = node.asString("").strip();
        return s.isEmpty() ? fallback : s;
    }

    /** The market sees a pseudonym, never the account name (upstream {@code skillhubUserKey}). */
    private static String userKey(String username) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(("loopforge4j:" + username).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private String human(Principal principal) {
        CurrentUser user = access.requireHuman(principal, "use the skills market");
        return user.username();
    }

    private String admin(Principal principal) {
        String user = human(principal);
        access.requireAdmin(principal);
        return user;
    }
}
