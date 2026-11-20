package io.github.loopforge4j.core.runtime.managed;

import io.github.loopforge4j.core.json.Json;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

/**
 * Platform-owned agent CLIs (upstream {@code managedcli}): Claude Code, Codex and CodeBuddy are
 * installed from the public npm registry into immutable version directories, updated once a day
 * (and on demand) to the newest stable release that has been public for a full day, verified
 * against the registry's SHA-512 integrity and probed for the arguments the executors use, and
 * rolled back explicitly. A run pins the versions it started with, so an update never changes a
 * run halfway.
 */
public final class ManagedClis {

    /** The registry, packages and safety windows are fixed: no setting may change them. */
    public static final String NPM_REGISTRY = "https://registry.npmjs.org";

    public static final Duration OBSERVATION = Duration.ofHours(24);
    static final Duration COMMAND_TIMEOUT = Duration.ofMinutes(10);
    static final ZoneId BEIJING = ZoneId.of("Asia/Shanghai");
    static final Pattern RELEASE = Pattern.compile("(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)");
    static final Pattern OUTPUT_VERSION =
            Pattern.compile("(?:^|[^0-9.])([0-9]+\\.[0-9]+\\.[0-9]+)(?:$|[^0-9.A-Za-z+-])");
    static final long MAX_METADATA = 64L << 20;

    /** One supported CLI: its package, executable, help command and the arguments executors need. */
    public record Provider(String name, String pkg, String binary, List<String> help, List<String> flags) {}

    public static final Map<String, Provider> PROVIDERS;

    static {
        Map<String, Provider> p = new TreeMap<>();
        p.put(
                "claude-code",
                new Provider(
                        "claude-code",
                        "@anthropic-ai/claude-code",
                        "claude",
                        List.of("--help"),
                        List.of(
                                "--print",
                                "--output-format",
                                "--verbose",
                                "--permission-mode",
                                "--setting-sources",
                                "--system-prompt-file",
                                "--mcp-config",
                                "--settings",
                                "--resume",
                                "--plugin-dir")));
        p.put(
                "codebuddy",
                new Provider(
                        "codebuddy",
                        "@tencent-ai/codebuddy-code",
                        "codebuddy",
                        List.of("--help"),
                        List.of(
                                "--print",
                                "--output-format",
                                "--permission-mode",
                                "--setting-sources",
                                "--system-prompt-file",
                                "--mcp-config",
                                "--strict-mcp-config",
                                "--settings",
                                "--resume",
                                "--plugin-dir")));
        p.put(
                "codex",
                new Provider(
                        "codex",
                        "@openai/codex",
                        "codex",
                        List.of("exec", "--help"),
                        List.of("--json", "--sandbox", "--skip-git-repo-check", "--model")));
        PROVIDERS = java.util.Collections.unmodifiableMap(p);
    }

    /** The managed CLI behind an executor kind. */
    public static Optional<String> providerFor(String executorKind) {
        return Optional.ofNullable(
                switch (executorKind == null ? "" : executorKind) {
                    case "claude", "claude-code" -> "claude-code";
                    case "codex" -> "codex";
                    case "codebuddy" -> "codebuddy";
                    default -> null;
                });
    }

    /** One immutable, version-specific installation. */
    public record Installation(String version, String binary, String integrity, Instant installedAt) {}

    /** The updater's state, committed with one atomic rename. */
    public record State(
            Instant lastCheck,
            Map<String, Installation> candidates,
            Map<String, Installation> current,
            Map<String, Installation> previous,
            Map<String, String> rejected,
            Instant updatedAt) {

        public State {
            candidates = candidates == null ? new TreeMap<>() : new TreeMap<>(candidates);
            current = current == null ? new TreeMap<>() : new TreeMap<>(current);
            previous = previous == null ? new TreeMap<>() : new TreeMap<>(previous);
            rejected = rejected == null ? new TreeMap<>() : new TreeMap<>(rejected);
        }

        static State empty() {
            return new State(null, null, null, null, null, null);
        }
    }

    /** What happened to one CLI in a check, update or rollback. */
    public record Result(String provider, String from, String to, String status, String message, String validation) {}

    public record Report(String action, Instant startedAt, Instant finishedAt, List<Result> results, String error) {}

    /** Runs a program (never a shell): its exit code and combined output. */
    public interface CommandRunner {
        Output run(String program, List<String> args, Path directory, Map<String, String> environment, Duration timeout)
                throws IOException;
    }

    public record Output(int exit, String text) {}

    /** A release of a package as the registry describes it. */
    record Release(String version, String integrity, Instant published) {}

    private final Path root;
    private final HttpClient http;
    private final String registry;
    private final CommandRunner commands;
    private final Clock clock;
    private final ReentrantLock lock = new ReentrantLock();
    private String node;
    private String npm;

    public ManagedClis(Path root, Clock clock) {
        this(
                root,
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(30))
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build(),
                NPM_REGISTRY,
                ManagedClis::execute,
                clock);
    }

    /** For tests: another registry and command runner (tests must never install real CLIs). */
    public ManagedClis(Path root, HttpClient http, String registry, CommandRunner commands, Clock clock) {
        if (!root.isAbsolute() || !root.normalize().equals(root) || root.getParent() == null) {
            throw new IllegalArgumentException("managed CLI root must be a clean absolute non-root directory");
        }
        this.root = root;
        this.http = http;
        this.registry = registry;
        this.commands = commands;
        this.clock = clock;
    }

    public Path root() {
        return root;
    }

    /** For tests: the node and npm executables, instead of looking them up on PATH. */
    ManagedClis withTools(String nodeBinary, String npmBinary) {
        this.node = nodeBinary;
        this.npm = npmBinary;
        return this;
    }

    // ------------------------------------------------------------------ state

    public State state() throws IOException {
        Path file = root.resolve("state.json");
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            return State.empty();
        }
        State state = Json.read(readSmall(file, 1 << 20), State.class);
        validate(state.current());
        return state;
    }

    public Optional<Report> lastReport() throws IOException {
        Path file = root.resolve("last-report.json");
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            return Optional.empty();
        }
        return Optional.of(Json.read(readSmall(file, 1 << 20), Report.class));
    }

    /** The versions a run uses: fixed the first time it asks, whatever updates come later. */
    public Map<String, Installation> pin(String runKey) throws IOException {
        if (runKey == null || runKey.isBlank()) {
            throw new IllegalArgumentException("managed CLI run key is required");
        }
        Path pins = directory("pins");
        Path file = pins.resolve(sha256(runKey) + ".json");
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            Map<String, Installation> current = state().current();
            Path tmp = Files.createTempFile(pins, ".pin-", ".json");
            try {
                Files.writeString(tmp, Json.write(current));
                try {
                    // a link publishes a complete file; the first run to pin wins
                    Files.createLink(file, tmp);
                } catch (FileAlreadyExistsException e) {
                    // pinned concurrently
                }
            } finally {
                Files.deleteIfExists(tmp);
            }
        }
        Map<String, Installation> pinned = Json.mapper()
                .readerFor(Json.mapper()
                        .getTypeFactory()
                        .constructMapType(TreeMap.class, String.class, Installation.class))
                .readValue(readSmall(file, 1 << 20));
        validate(pinned);
        return pinned;
    }

    /** The managed executable a run uses for {@code provider}, if one is installed. */
    public Optional<String> binaryFor(String runKey, String provider) throws IOException {
        Installation installed = pin(runKey).get(provider);
        return installed == null ? Optional.empty() : Optional.of(installed.binary());
    }

    // ------------------------------------------------------------------ operations

    /** Today's scheduled check: at most once per day (Beijing time), never before its window. */
    public Optional<Report> check() throws IOException {
        return run("check", null);
    }

    /** Updates every CLI now (or only {@code provider}). */
    public Report update(String provider) throws IOException {
        if (provider != null && !PROVIDERS.containsKey(provider)) {
            throw new IllegalArgumentException("unsupported managed CLI " + provider);
        }
        return run("update", provider).orElseThrow();
    }

    /** Back to the previous version; the rejected one is never installed again automatically. */
    public Report rollback(String provider) throws IOException {
        if (!PROVIDERS.containsKey(provider)) {
            throw new IllegalArgumentException("rollback requires a known provider");
        }
        return run("rollback", provider).orElseThrow();
    }

    private Optional<Report> run(String action, String provider) throws IOException {
        directory("");
        lock.lock();
        try (FileChannel channel = FileChannel.open(
                        root.resolve("updater.lock"),
                        StandardOpenOption.CREATE,
                        StandardOpenOption.WRITE,
                        LinkOption.NOFOLLOW_LINKS);
                FileLock ignored = channel.lock()) {
            Instant now = clock.instant();
            State state = state();
            if ("check".equals(action)) {
                if (now.isBefore(scheduledAt(root, now))) {
                    return Optional.empty();
                }
                if (state.lastCheck() != null && !day(state.lastCheck()).isBefore(day(now))) {
                    return Optional.empty();
                }
                // a failed or interrupted check still uses up today's window
                state = new State(
                        now,
                        state.candidates(),
                        state.current(),
                        state.previous(),
                        state.rejected(),
                        state.updatedAt());
                save(state);
            }
            List<Result> results = new ArrayList<>();
            String error = null;
            try {
                if ("rollback".equals(action)) {
                    rollbackLocked(provider, state, results);
                } else {
                    updateLocked(
                            provider == null ? List.copyOf(PROVIDERS.keySet()) : List.of(provider), state, results);
                }
            } catch (IOException | RuntimeException e) {
                error = e.getMessage();
            }
            Report report = new Report(action, now, clock.instant(), List.copyOf(results), error);
            atomicJson(root.resolve("last-report.json"), report);
            if (error != null && "rollback".equals(action)) {
                throw new IOException(error);
            }
            return Optional.of(report);
        } finally {
            lock.unlock();
        }
    }

    private void updateLocked(List<String> providers, State state, List<Result> results) throws IOException {
        Map<String, Installation> staged = new TreeMap<>();
        List<String> failures = new ArrayList<>();
        boolean toolsReady = false;
        for (String name : providers) {
            String from = state.current().containsKey(name)
                    ? state.current().get(name).version()
                    : null;
            String to = null;
            String status = null;
            String message = null;
            String validation = null;
            try {
                Release candidate = candidate(PROVIDERS.get(name));
                to = candidate.version();
                String[] gate = gate(state, name, from, candidate);
                status = gate[0];
                message = gate[1];
                if (status == null) {
                    if (!toolsReady) {
                        prepareTools();
                        toolsReady = true;
                    }
                    Installation installed = stage(state, name, candidate);
                    staged.put(name, installed);
                    status = "staged";
                    validation = "version-and-required-arguments";
                }
            } catch (IOException | RuntimeException e) {
                status = "failed";
                message = e.getMessage();
                failures.add(name + ": " + e.getMessage());
            }
            results.add(new Result(name, from, to, status, message, validation));
        }
        if (!staged.isEmpty()) {
            Map<String, Installation> current = new TreeMap<>(state.current());
            Map<String, Installation> previous = new TreeMap<>(state.previous());
            Map<String, String> rejected = new TreeMap<>(state.rejected());
            staged.forEach((name, installed) -> {
                if (current.containsKey(name)) {
                    previous.put(name, current.get(name));
                }
                current.put(name, installed);
                rejected.remove(name);
            });
            validate(current);
            save(new State(state.lastCheck(), state.candidates(), current, previous, rejected, clock.instant()));
            for (int i = 0; i < results.size(); i++) {
                Result r = results.get(i);
                if ("staged".equals(r.status())) {
                    results.set(i, new Result(r.provider(), r.from(), r.to(), "updated", r.message(), r.validation()));
                }
            }
        }
        if (!failures.isEmpty()) {
            throw new IOException(String.join("; ", failures));
        }
    }

    /** Why a release must not be installed, or {null, null} when it may. */
    String[] gate(State state, String provider, String from, Release candidate) {
        if (candidate.version().equals(from)) {
            return new String[] {"unchanged", null};
        }
        if (from != null && compare(candidate.version(), from) < 0) {
            return new String[] {"skipped", "automatic downgrade refused"};
        }
        if (candidate.version().equals(state.rejected().get(provider))) {
            return new String[] {"skipped", "this release was rolled back; waiting for a newer release"};
        }
        if (clock.instant().isBefore(candidate.published().plus(OBSERVATION))) {
            return new String[] {"skipped", "release is still in the observation window"};
        }
        return new String[] {null, null};
    }

    private void rollbackLocked(String provider, State state, List<Result> results) throws IOException {
        Installation previous = state.previous().get(provider);
        if (previous == null) {
            throw new IOException("no previous managed version available");
        }
        Installation current = state.current().get(provider);
        prepareTools();
        probe(provider, previous);
        Map<String, Installation> cur = new TreeMap<>(state.current());
        Map<String, Installation> prev = new TreeMap<>(state.previous());
        Map<String, String> rejected = new TreeMap<>(state.rejected());
        if (current != null) {
            rejected.put(provider, current.version());
            prev.put(provider, current);
        }
        cur.put(provider, previous);
        validate(cur);
        save(new State(state.lastCheck(), state.candidates(), cur, prev, rejected, clock.instant()));
        results.add(new Result(
                provider, current == null ? null : current.version(), previous.version(), "rolled-back", null, null));
    }

    // ------------------------------------------------------------------ registry

    /** The newest stable release no newer than the latest tag that has been public for a day. */
    Release candidate(Provider provider) throws IOException {
        JsonNode metadata = metadata(provider.pkg());
        String latest = metadata.path("dist-tags").path("latest").asString("");
        if (!provider.pkg().equals(metadata.path("name").asString(""))
                || !RELEASE.matcher(latest).matches()) {
            throw new IOException("registry did not provide the expected package and a stable release version");
        }
        String version = latest;
        String mature = mature(metadata, latest, clock.instant().minus(OBSERVATION));
        if (mature != null) {
            version = mature;
        }
        JsonNode release = metadata.path("versions").path(version);
        if (!provider.pkg().equals(release.path("name").asString(""))
                || !version.equals(release.path("version").asString(""))) {
            throw new IOException("release missing or inconsistent in registry metadata");
        }
        if (release.hasNonNull("deprecated")
                && !release.path("deprecated").asString("").isEmpty()) {
            throw new IOException("candidate release is deprecated");
        }
        String integrity = release.path("dist").path("integrity").asString("");
        if (!validIntegrity(integrity)) {
            throw new IOException("candidate requires a valid SHA512 package integrity value");
        }
        Instant published = published(metadata, version);
        if (published == null) {
            throw new IOException("candidate release has no valid publication time");
        }
        return new Release(version, integrity, published);
    }

    static String mature(JsonNode metadata, String upper, Instant cutoff) {
        String best = null;
        for (Map.Entry<String, JsonNode> e : metadata.path("versions").properties()) {
            String v = e.getKey();
            if (!RELEASE.matcher(v).matches()
                    || compare(v, upper) > 0
                    || !e.getValue().path("deprecated").asString("").isEmpty()) {
                continue;
            }
            Instant published = published(metadata, v);
            if (published == null || published.isAfter(cutoff)) {
                continue;
            }
            if (best == null || compare(v, best) > 0) {
                best = v;
            }
        }
        return best;
    }

    private static Instant published(JsonNode metadata, String version) {
        try {
            String text = metadata.path("time").path(version).asString("");
            return text.isEmpty() ? null : Instant.parse(text);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private JsonNode metadata(String pkg) throws IOException {
        // the full document: abbreviated metadata has no publication times
        URI uri = URI.create(
                registry + "/" + URLEncoder.encode(pkg, StandardCharsets.UTF_8).replace("%40", "@"));
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofMinutes(2))
                .header("Accept", "application/json")
                .GET()
                .build();
        try {
            HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream body = response.body()) {
                if (response.statusCode() != 200) {
                    throw new IOException("package metadata HTTP " + response.statusCode());
                }
                byte[] data = body.readNBytes((int) MAX_METADATA + 1);
                if (data.length > MAX_METADATA) {
                    throw new IOException("package metadata exceeds size limit");
                }
                return Json.mapper().readTree(data);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted");
        } catch (RuntimeException e) {
            throw new IOException("invalid package metadata: " + e.getMessage());
        }
    }

    static boolean validIntegrity(String value) {
        if (value == null || !value.startsWith("sha512-")) {
            return false;
        }
        try {
            return Base64.getDecoder().decode(value.substring(7)).length == 64;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    static int compare(String a, String b) {
        String[] left = a.split("\\.");
        String[] right = b.split("\\.");
        for (int i = 0; i < 3; i++) {
            int c = Long.compare(Long.parseLong(left[i]), Long.parseLong(right[i]));
            if (c != 0) {
                return c;
            }
        }
        return 0;
    }

    // ------------------------------------------------------------------ installation

    private Map<String, String> environment() {
        Map<String, String> env = new LinkedHashMap<>();
        // an allowlist: no platform tokens, provider keys, task credentials or NODE_OPTIONS
        for (String key : List.of(
                "LANG",
                "LC_ALL",
                "HTTPS_PROXY",
                "HTTP_PROXY",
                "ALL_PROXY",
                "NO_PROXY",
                "https_proxy",
                "http_proxy",
                "all_proxy",
                "no_proxy",
                "SSL_CERT_FILE",
                "SSL_CERT_DIR",
                "NODE_EXTRA_CA_CERTS")) {
            String value = System.getenv(key);
            if (value != null) {
                env.put(key, value);
            }
        }
        String path = System.getenv("PATH");
        if (path == null || path.isBlank()) {
            path = "/usr/local/bin:/usr/bin:/bin";
        }
        if (node != null) {
            path = Path.of(node).getParent() + java.io.File.pathSeparator + path;
        }
        Path home = root.resolve("updater-home");
        env.put("PATH", path);
        env.put("HOME", home.toString());
        env.put("TMPDIR", root.resolve("tmp").toString());
        env.put("CODEX_HOME", home.resolve(".codex").toString());
        env.put("CLAUDE_CONFIG_DIR", home.resolve(".claude").toString());
        env.put("CODEBUDDY_CONFIG_DIR", home.resolve(".codebuddy").toString());
        env.put("DISABLE_AUTOUPDATER", "1");
        env.put("DISABLE_UPDATES", "1");
        env.put("CI", "1");
        env.put("NO_COLOR", "1");
        env.put("NPM_CONFIG_USERCONFIG", home.resolve("npmrc-user").toString());
        env.put("NPM_CONFIG_GLOBALCONFIG", home.resolve("npmrc-global").toString());
        env.put("NPM_CONFIG_CACHE", root.resolve("npm-cache").toString());
        return env;
    }

    private Output command(String program, List<String> args, Path directory) throws IOException {
        return commands.run(program, args, directory, environment(), COMMAND_TIMEOUT);
    }

    private void prepareTools() throws IOException {
        for (String dir : List.of("updater-home", "tmp", "npm-cache", "providers")) {
            directory(dir);
        }
        for (String name : List.of("npmrc-user", "npmrc-global")) {
            Files.writeString(root.resolve("updater-home").resolve(name), "");
        }
        if (node == null) {
            node = locate("node");
        }
        if (npm == null) {
            npm = locate("npm");
        }
        Output out = command(node, List.of("--version"), root);
        String version = out.text().strip().replaceFirst("^v", "");
        if (out.exit() != 0 || !RELEASE.matcher(version).matches()) {
            throw new IOException("cannot identify Node.js version");
        }
        if (Integer.parseInt(version.split("\\.")[0]) < 22) {
            throw new IOException("managed CLI updater requires Node.js 22 or newer");
        }
    }

    private static String locate(String name) throws IOException {
        String path = System.getenv("PATH");
        for (String dir : (path == null ? "/usr/local/bin:/usr/bin:/bin" : path).split(java.io.File.pathSeparator)) {
            if (dir.isBlank()) {
                continue;
            }
            Path candidate = Path.of(dir, name);
            if (Files.isExecutable(candidate)) {
                return candidate.toAbsolutePath().toString();
            }
        }
        throw new IOException("managed CLI updater requires " + name);
    }

    /** Reuses a cached candidate that still matches the release, else installs it; then probes it. */
    private Installation stage(State state, String provider, Release candidate) throws IOException {
        Installation cached = state.candidates().get(provider);
        Installation installed = cached;
        boolean usable = cached != null
                && cached.version().equals(candidate.version())
                && candidate.integrity().equals(cached.integrity());
        if (usable) {
            try {
                validate(Map.of(provider, cached));
            } catch (IOException e) {
                usable = false;
            }
        }
        if (!usable) {
            installed = install(provider, candidate);
            // cached before the probe: a failed probe does not cause another download
            Map<String, Installation> candidates = new TreeMap<>(state.candidates());
            candidates.put(provider, installed);
            State next = new State(
                    state.lastCheck(),
                    candidates,
                    state.current(),
                    state.previous(),
                    state.rejected(),
                    state.updatedAt());
            save(next);
            state.candidates().put(provider, installed);
        }
        probe(provider, installed);
        return installed;
    }

    private Installation install(String provider, Release candidate) throws IOException {
        Provider spec = PROVIDERS.get(provider);
        Path base = directory("providers/" + provider);
        Path stage = Files.createTempDirectory(base, ".staging-");
        try {
            Files.writeString(stage.resolve("package.json"), "{\"private\":true}\n");
            Output out = command(
                    npm,
                    List.of(
                            "install",
                            "--prefix",
                            stage.toString(),
                            "--registry",
                            NPM_REGISTRY,
                            "--no-audit",
                            "--no-fund",
                            "--engine-strict",
                            "--ignore-scripts",
                            "--include=optional",
                            "--save-exact",
                            spec.pkg() + "@" + candidate.version()),
                    stage);
            if (out.exit() != 0) {
                throw new IOException("install " + provider + ": " + tail(out.text()));
            }
            verifyLock(stage, spec.pkg(), candidate);
            // lifecycle hooks of native optional dependencies run only after the integrity check
            out = command(
                    npm,
                    List.of(
                            "rebuild",
                            "--prefix",
                            stage.toString(),
                            "--registry",
                            NPM_REGISTRY,
                            "--no-audit",
                            "--no-fund"),
                    stage);
            if (out.exit() != 0) {
                throw new IOException("build verified CLI dependencies: " + tail(out.text()));
            }
            verifyLock(stage, spec.pkg(), candidate);
            Path finalDir = base.resolve(
                    candidate.version() + "-" + stage.getFileName().toString().substring(".staging-".length()));
            if (Files.exists(finalDir, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("candidate installation target already exists");
            }
            try {
                Files.move(stage, finalDir, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(stage, finalDir);
            }
            stage = null;
            Installation installed = new Installation(
                    candidate.version(),
                    finalDir.resolve("node_modules/.bin").resolve(spec.binary()).toString(),
                    candidate.integrity(),
                    clock.instant());
            validate(Map.of(provider, installed));
            return installed;
        } finally {
            if (stage != null) {
                delete(stage);
            }
        }
    }

    static void verifyLock(Path dir, String pkg, Release candidate) throws IOException {
        Path lock = dir.resolve("package-lock.json");
        if (!Files.isRegularFile(lock, LinkOption.NOFOLLOW_LINKS) || Files.size(lock) > (16 << 20)) {
            throw new IOException("missing or invalid installed npm lockfile");
        }
        JsonNode entry =
                Json.mapper().readTree(Files.readString(lock)).path("packages").path("node_modules/" + pkg);
        if (!candidate.version().equals(entry.path("version").asString(""))
                || !candidate.integrity().equals(entry.path("integrity").asString(""))) {
            throw new IOException("installed npm package does not match the verified version/integrity");
        }
    }

    /** The executable reports the expected version and advertises every argument executors use. */
    private void probe(String provider, Installation installed) throws IOException {
        validate(Map.of(provider, installed));
        Output version = command(installed.binary(), List.of("--version"), root);
        Matcher m = OUTPUT_VERSION.matcher(version.text().strip());
        if (version.exit() != 0 || !m.find() || !m.group(1).equals(installed.version())) {
            throw new IOException("candidate executable reports an unexpected version");
        }
        Provider spec = PROVIDERS.get(provider);
        Output help = command(installed.binary(), spec.help(), root);
        if (help.exit() != 0) {
            throw new IOException("argument compatibility probe failed");
        }
        for (String flag : spec.flags()) {
            if (!Pattern.compile(Pattern.quote(flag) + "(?:[\\s,=<\\[\\]]|$)", Pattern.MULTILINE)
                    .matcher(help.text())
                    .find()) {
                if ("claude-code".equals(provider) && "--system-prompt-file".equals(flag)) {
                    probeHiddenSystemPromptFile(installed);
                    continue;
                }
                throw new IOException("candidate CLI does not advertise required argument " + flag);
            }
        }
    }

    /**
     * Claude Code hides {@code --system-prompt-file} from its help, and its help succeeds for unknown
     * flags too: require an unknown-option refusal and the exact missing-file error instead.
     */
    private void probeHiddenSystemPromptFile(Installation installed) throws IOException {
        Path dir = Files.createTempDirectory(directory("tmp"), "argument-probe-");
        try {
            String missing = dir.resolve("nonexistent-system-prompt.txt").toString();
            String unknown = "--loopforge-invalid-option-probe";
            Output negative = command(installed.binary(), List.of(unknown, missing, "--print"), root);
            if (negative.exit() == 0
                    || !negative.text()
                            .toLowerCase(java.util.Locale.ROOT)
                            .contains("unknown option '" + unknown + "'")) {
                throw new IOException(
                        "cannot verify hidden --system-prompt-file: unknown-option control was not rejected");
            }
            Output positive = command(installed.binary(), List.of("--system-prompt-file", missing, "--print"), root);
            if (positive.exit() == 0
                    || !positive.text().strip().equals("Error: System prompt file not found: " + missing)) {
                throw new IOException("candidate CLI did not confirm the hidden --system-prompt-file argument");
            }
        } finally {
            Files.deleteIfExists(dir);
        }
    }

    // ------------------------------------------------------------------ files

    /** Every executable stays a regular file inside its own provider directory. */
    void validate(Map<String, Installation> inventory) throws IOException {
        for (Map.Entry<String, Installation> e : inventory.entrySet()) {
            String provider = e.getKey();
            Installation installed = e.getValue();
            if (!PROVIDERS.containsKey(provider)) {
                throw new IOException("unknown managed CLI provider " + provider);
            }
            if (installed == null
                    || installed.version() == null
                    || !RELEASE.matcher(installed.version()).matches()) {
                throw new IOException("invalid release version for " + provider);
            }
            Path base = root.resolve("providers").resolve(provider);
            Path binary = Path.of(installed.binary());
            if (!binary.isAbsolute()
                    || !binary.normalize().startsWith(base)
                    || binary.normalize().equals(base)) {
                throw new IOException(provider + " executable escapes its managed installation");
            }
            Path realBase = base.toRealPath();
            if (!realBase.equals(root.toRealPath().resolve("providers").resolve(provider))) {
                throw new IOException("managed provider directory escapes root");
            }
            Path real = binary.toRealPath();
            if (!real.startsWith(realBase)) {
                throw new IOException("managed executable symlink escapes installation");
            }
            if (!Files.isRegularFile(real) || !Files.isExecutable(real)) {
                throw new IOException("managed CLI " + provider + " is not executable");
            }
        }
    }

    private Path directory(String rel) throws IOException {
        if (Files.isSymbolicLink(root)) {
            throw new IOException("managed CLI root must be a real directory, not a symlink");
        }
        Files.createDirectories(root);
        Path path = root;
        if (!rel.isEmpty()) {
            for (String part : rel.split("/")) {
                if (part.isEmpty() || part.equals(".") || part.equals("..")) {
                    throw new IOException("invalid managed CLI subdirectory");
                }
                path = path.resolve(part);
                try {
                    Files.createDirectory(path);
                } catch (FileAlreadyExistsException e) {
                    // exists
                }
                if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("managed CLI directory is not a real directory: " + path);
                }
            }
        }
        return path;
    }

    private void save(State state) throws IOException {
        atomicJson(root.resolve("state.json"), state);
    }

    private static void atomicJson(Path file, Object value) throws IOException {
        Path tmp = Files.createTempFile(file.getParent(), ".tmp-", ".json");
        try {
            Files.writeString(tmp, Json.write(value));
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private static String readSmall(Path file, long limit) throws IOException {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > limit) {
            throw new IOException("invalid managed CLI JSON file: " + file.getFileName());
        }
        return Files.readString(file);
    }

    private static void delete(Path dir) {
        try (var walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    // left behind
                }
            });
        } catch (IOException e) {
            // gone
        }
    }

    private static String tail(String text) {
        String t = text == null ? "" : text.strip();
        return t.length() > 500 ? t.substring(t.length() - 500) : t;
    }

    static String sha256(String text) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------------ schedule

    private static LocalDate day(Instant at) {
        return at.atZone(BEIJING).toLocalDate();
    }

    /** Today's window: 04:00 Beijing time plus up to ten minutes, spread by host and date. */
    public static Instant scheduledAt(Path root, Instant now) {
        LocalDate date = day(now);
        byte[] sum;
        try {
            sum = MessageDigest.getInstance("SHA-256")
                    .digest((root + "\u0000" + date).getBytes(StandardCharsets.UTF_8));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        long jitter = Long.remainderUnsigned(java.nio.ByteBuffer.wrap(sum, 0, 8).getLong(), 601);
        return ZonedDateTime.of(date.atTime(4, 0), BEIJING).toInstant().plusSeconds(jitter);
    }

    /** The next window strictly after {@code now}. */
    public static Instant nextScheduledAt(Path root, Instant now) {
        Instant at = scheduledAt(root, now);
        return at.isAfter(now)
                ? at
                : scheduledAt(root, now.atZone(BEIJING).plusDays(1).toInstant());
    }

    /** Runs a program with a clean environment and a time limit. */
    static Output execute(String program, List<String> args, Path directory, Map<String, String> env, Duration timeout)
            throws IOException {
        List<String> argv = new ArrayList<>();
        argv.add(program);
        argv.addAll(args);
        ProcessBuilder builder =
                new ProcessBuilder(argv).directory(directory.toFile()).redirectErrorStream(true);
        builder.environment().clear();
        builder.environment().putAll(env);
        builder.redirectInput(ProcessBuilder.Redirect.from(new java.io.File("/dev/null")));
        Process process = builder.start();
        java.util.concurrent.CompletableFuture<byte[]> output = java.util.concurrent.CompletableFuture.supplyAsync(
                () -> {
                    try (InputStream in = process.getInputStream()) {
                        return in.readNBytes(4 << 20);
                    } catch (IOException e) {
                        return new byte[0];
                    }
                },
                Thread::startVirtualThread);
        try {
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                throw new IOException("CLI command timeout: " + Path.of(program).getFileName());
            }
            return new Output(
                    process.exitValue(), new String(output.get(10, TimeUnit.SECONDS), StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException("interrupted");
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e) {
            return new Output(process.exitValue(), "");
        }
    }
}
