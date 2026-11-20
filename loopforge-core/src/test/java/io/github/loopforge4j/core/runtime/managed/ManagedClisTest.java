package io.github.loopforge4j.core.runtime.managed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import io.github.loopforge4j.core.json.Json;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The managed CLI updater against a fake registry and a fake npm: nothing real is downloaded or
 * installed, and no model credentials are touched.
 */
class ManagedClisTest {

    @TempDir
    Path dir;

    HttpServer registry;
    /** package → versions → publication time */
    final Map<String, Map<String, Instant>> published = new ConcurrentHashMap<>();

    final Map<String, String> latest = new ConcurrentHashMap<>();
    final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-06-10T12:00:00Z"));
    final List<String> commands = new ArrayList<>();
    /** what the fake CLI's --help prints */
    String help = "--print --output-format --verbose --permission-mode --setting-sources --system-prompt-file"
            + " --mcp-config --settings --resume --plugin-dir --strict-mcp-config --json --sandbox"
            + " --skip-git-repo-check --model";
    /** a version the fake npm installs wrongly (integrity mismatch) */
    String tampered;

    static String integrity(String pkg, String version) {
        byte[] sum = new byte[64];
        byte[] seed = (pkg + "@" + version).getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < 64; i++) {
            sum[i] = seed[i % seed.length];
        }
        return "sha512-" + Base64.getEncoder().encodeToString(sum);
    }

    @BeforeEach
    void start() throws Exception {
        registry = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        registry.createContext("/", exchange -> {
            String pkg = java.net.URLDecoder.decode(
                    exchange.getRequestURI().getRawPath().substring(1), StandardCharsets.UTF_8);
            Map<String, Instant> versions = published.get(pkg);
            byte[] body;
            int status = 200;
            if (versions == null) {
                status = 404;
                body = "{}".getBytes(StandardCharsets.UTF_8);
            } else {
                Map<String, Object> doc = new LinkedHashMap<>();
                doc.put("name", pkg);
                doc.put("dist-tags", Map.of("latest", latest.get(pkg)));
                Map<String, Object> vs = new LinkedHashMap<>();
                Map<String, Object> times = new LinkedHashMap<>();
                versions.forEach((v, t) -> {
                    vs.put(v, Map.of("name", pkg, "version", v, "dist", Map.of("integrity", integrity(pkg, v))));
                    times.put(v, t.toString());
                });
                doc.put("versions", vs);
                doc.put("time", times);
                body = Json.write(doc).getBytes(StandardCharsets.UTF_8);
            }
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        registry.start();
    }

    @AfterEach
    void stop() {
        registry.stop(0);
    }

    Clock clock() {
        return new Clock() {
            @Override
            public ZoneOffset getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(java.time.ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return now.get();
            }
        };
    }

    /** node prints its version; npm "installs" a script CLI and a lockfile; the CLI answers probes. */
    ManagedClis.Output fake(
            String program, List<String> args, Path directory, Map<String, String> env, Duration timeout)
            throws java.io.IOException {
        commands.add(Path.of(program).getFileName() + " " + String.join(" ", args));
        assertThat(env).doesNotContainKeys("ANTHROPIC_API_KEY", "OPENAI_API_KEY", "NODE_OPTIONS");
        assertThat(env.get("HOME")).startsWith(dir.toString());
        if (program.endsWith("/node")) {
            return new ManagedClis.Output(0, "v22.4.0\n");
        }
        if (program.endsWith("/npm")) {
            if (args.getFirst().equals("rebuild")) {
                return new ManagedClis.Output(0, "rebuilt");
            }
            String spec = args.getLast();
            int at = spec.lastIndexOf('@');
            String pkg = spec.substring(0, at);
            String version = spec.substring(at + 1);
            Path stage = Path.of(args.get(args.indexOf("--prefix") + 1));
            String binary = ManagedClis.PROVIDERS.values().stream()
                    .filter(p -> p.pkg().equals(pkg))
                    .findFirst()
                    .orElseThrow()
                    .binary();
            Path bin = Files.createDirectories(stage.resolve("node_modules/.bin"));
            Path cli = bin.resolve(binary);
            Files.writeString(cli, "#!/bin/sh\necho " + version + "\n");
            cli.toFile().setExecutable(true);
            String lockIntegrity = version.equals(tampered) ? integrity(pkg, "0.0.0") : integrity(pkg, version);
            Files.writeString(
                    stage.resolve("package-lock.json"),
                    Json.write(Map.of(
                            "packages",
                            Map.of("node_modules/" + pkg, Map.of("version", version, "integrity", lockIntegrity)))));
            return new ManagedClis.Output(0, "added 1 package");
        }
        // the installed CLI
        if (args.equals(List.of("--version"))) {
            return new ManagedClis.Output(
                    0,
                    Files.readString(Path.of(program)).lines().toList().get(1).substring(5) + " (CLI)\n");
        }
        return new ManagedClis.Output(0, help);
    }

    ManagedClis manager() {
        return new ManagedClis(
                        dir.resolve("managed"),
                        HttpClient.newHttpClient(),
                        "http://127.0.0.1:" + registry.getAddress().getPort(),
                        this::fake,
                        clock())
                .withTools("/opt/fake-node/bin/node", "/opt/fake-node/bin/npm");
    }

    void release(String pkg, String version, Instant at) {
        published.computeIfAbsent(pkg, p -> new ConcurrentHashMap<>()).put(version, at);
        latest.merge(pkg, version, (a, b) -> ManagedClis.compare(a, b) >= 0 ? a : b);
    }

    @Test
    void installsMatureReleasesPinsRunsRollsBackAndNeverDowngrades() throws Exception {
        Instant old = now.get().minus(Duration.ofDays(3));
        release("@anthropic-ai/claude-code", "2.1.0", old);
        release("@openai/codex", "0.40.0", old);
        release("@tencent-ai/codebuddy-code", "1.0.0", old);
        // one fresh release: still in the observation window, so the newest mature one is chosen
        release("@anthropic-ai/claude-code", "2.2.0", now.get().minus(Duration.ofHours(2)));

        ManagedClis clis = manager();
        ManagedClis.Report report = clis.update(null);
        assertThat(report.error()).isNull();
        assertThat(report.results())
                .extracting(ManagedClis.Result::provider, ManagedClis.Result::to, ManagedClis.Result::status)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("claude-code", "2.1.0", "updated"),
                        org.assertj.core.groups.Tuple.tuple("codebuddy", "1.0.0", "updated"),
                        org.assertj.core.groups.Tuple.tuple("codex", "0.40.0", "updated"));
        assertThat(commands).anyMatch(c -> c.startsWith("npm install") && c.contains("--ignore-scripts"));
        assertThat(clis.state().current().get("claude-code").version()).isEqualTo("2.1.0");

        // a run keeps the versions it started with
        String pinned = clis.binaryFor("run-1", "claude-code").orElseThrow();
        assertThat(pinned).contains("/providers/claude-code/2.1.0-");

        // a day later 2.2.0 is mature: it is installed, the run stays on 2.1.0, new runs get 2.2.0
        now.set(now.get().plus(Duration.ofDays(1)));
        assertThat(clis.update("claude-code").results())
                .singleElement()
                .satisfies(r -> assertThat(r.status()).isEqualTo("updated"));
        assertThat(clis.binaryFor("run-1", "claude-code").orElseThrow()).isEqualTo(pinned);
        assertThat(clis.binaryFor("run-2", "claude-code").orElseThrow()).contains("/2.2.0-");
        assertThat(clis.update("claude-code").results().getFirst().status()).isEqualTo("unchanged");

        // rollback restores 2.1.0 and refuses to reinstall 2.2.0 automatically
        assertThat(clis.rollback("claude-code").results().getFirst().status()).isEqualTo("rolled-back");
        assertThat(clis.state().current().get("claude-code").version()).isEqualTo("2.1.0");
        assertThat(clis.update("claude-code").results().getFirst())
                .satisfies(r -> assertThat(r.message()).contains("rolled back"));
        assertThat(clis.lastReport())
                .hasValueSatisfying(r -> assertThat(r.action()).isEqualTo("update"));

        // a newer release supersedes the rejection
        release("@anthropic-ai/claude-code", "2.3.0", now.get().minus(Duration.ofDays(2)));
        assertThat(clis.update("claude-code").results().getFirst().status()).isEqualTo("updated");

        // never downgrade: the registry's latest going backwards is refused
        latest.put("@openai/codex", "0.39.0");
        release("@openai/codex", "0.39.0", old);
        latest.put("@openai/codex", "0.39.0");
        assertThat(clis.update("codex").results().getFirst())
                .satisfies(r -> assertThat(r.status()).isIn("skipped", "unchanged"));
    }

    @Test
    void releasesThatDoNotVerifyAreNeverActivated() throws Exception {
        Instant old = now.get().minus(Duration.ofDays(3));
        release("@openai/codex", "0.40.0", old);
        ManagedClis clis = manager();

        // npm installed something else than the registry's integrity promised
        tampered = "0.40.0";
        ManagedClis.Report bad = clis.update("codex");
        assertThat(bad.results().getFirst().status()).isEqualTo("failed");
        assertThat(bad.results().getFirst().message()).contains("integrity");
        assertThat(clis.state().current()).isEmpty();

        // a CLI that lacks an argument the executors pass is refused
        tampered = null;
        help = "--json --model";
        ManagedClis.Report missing = clis.update("codex");
        assertThat(missing.results().getFirst().message()).contains("--sandbox");
        assertThat(clis.state().current()).isEmpty();
        assertThat(clis.binaryFor("run-x", "codex")).isEmpty();

        assertThatThrownBy(() -> clis.update("vim")).hasMessageContaining("unsupported");
        assertThatThrownBy(() -> clis.rollback("codex")).hasMessageContaining("no previous");
    }

    @Test
    void theDailyCheckRunsOnceInItsWindow() throws Exception {
        release("@openai/codex", "0.40.0", now.get().minus(Duration.ofDays(3)));
        ManagedClis clis = manager();
        Path root = dir.resolve("managed");
        Instant window = ManagedClis.scheduledAt(root, now.get());
        assertThat(window.atZone(ManagedClis.BEIJING).getHour()).isEqualTo(4);
        now.set(ManagedClis.nextScheduledAt(root, now.get()).minusSeconds(1));
        assertThat(clis.check()).isEmpty();
        now.set(now.get().plusSeconds(2));
        assertThat(clis.check()).isPresent();
        assertThat(clis.check()).isEmpty();
        assertThat(ManagedClis.nextScheduledAt(root, now.get())).isAfter(now.get());
    }

    @Test
    void versionsCompareNumericallyAndIntegrityMustBeSha512() {
        assertThat(ManagedClis.compare("1.10.0", "1.9.9")).isPositive();
        assertThat(ManagedClis.compare("2.0.0", "2.0.0")).isZero();
        assertThat(ManagedClis.validIntegrity(integrity("a", "1.0.0"))).isTrue();
        assertThat(ManagedClis.validIntegrity("sha1-abc")).isFalse();
        assertThat(ManagedClis.providerFor("claude")).contains("claude-code");
        assertThat(ManagedClis.providerFor(io.github.loopforge4j.core.runtime.executor.ClaudeCodeExecutor.KIND))
                .contains("claude-code");
        assertThat(ManagedClis.providerFor(io.github.loopforge4j.core.runtime.executor.CodexExecutor.KIND))
                .contains("codex");
        assertThat(ManagedClis.providerFor(io.github.loopforge4j.core.runtime.executor.CodeBuddyExecutor.KIND))
                .contains("codebuddy");
        assertThat(ManagedClis.providerFor("opencode")).isEmpty();
    }
}
