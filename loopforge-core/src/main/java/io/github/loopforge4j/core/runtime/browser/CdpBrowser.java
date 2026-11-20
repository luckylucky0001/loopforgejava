package io.github.loopforge4j.core.runtime.browser;

import io.github.loopforge4j.core.json.Json;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

/**
 * A headless Chromium driven over the Chrome DevTools Protocol (upstream agent browser): launched
 * with its own throw-away profile, reached through the WebSocket it announces, closed with its
 * process tree. Commands are JSON-RPC calls; events go to the registered listeners.
 *
 * <p>Every target the browser creates (tabs, popups, out-of-process frames, workers) is attached
 * paused, gets request interception, and only then runs: each request it makes passes the
 * browser's policy first, and the rest fail as blocked by the client.
 */
public final class CdpBrowser implements AutoCloseable {

    /**
     * @param binary Chromium or Chrome
     * @param timeout limit for starting and for each command
     * @param noSandbox start without Chromium's own sandbox (needed when running as root)
     */
    public record Options(Path binary, Duration timeout, boolean noSandbox) {}

    private static final Pattern LISTENING = Pattern.compile("DevTools listening on (ws://\\S+)");

    private final Process process;
    private final Path profile;
    private final Duration timeout;
    private final AtomicInteger ids = new AtomicInteger();
    private final Map<Integer, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
    private final List<Consumer<JsonNode>> listeners = new CopyOnWriteArrayList<>();
    private final java.util.function.Predicate<String> allowed;
    private final List<String> blocked = new CopyOnWriteArrayList<>();
    /** The guarded session of each target, by target id. */
    private final Map<String, CompletableFuture<String>> sessions = new ConcurrentHashMap<>();

    private volatile WebSocket socket;

    private CdpBrowser(Process process, Path profile, Duration timeout, java.util.function.Predicate<String> allowed) {
        this.process = process;
        this.profile = profile;
        this.allowed = allowed;
        this.timeout = timeout;
    }

    /** Candidate binaries when none is configured. */
    public static List<Path> candidates() {
        List<Path> out = new ArrayList<>();
        String env = System.getenv("CHROME_BIN");
        if (env != null && !env.isBlank()) {
            out.add(Path.of(env));
        }
        for (String p : List.of(
                "/usr/bin/chromium",
                "/usr/bin/chromium-browser",
                "/usr/bin/google-chrome",
                "/usr/bin/google-chrome-stable",
                "/opt/pw-browsers/chromium/chrome-linux64/chrome",
                "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
                "/Applications/Chromium.app/Contents/MacOS/Chromium")) {
            out.add(Path.of(p));
        }
        // Playwright's browsers: a shared install and the user's cache (as upstream's bind script looks)
        List<Path> roots = new ArrayList<>(List.of(Path.of("/opt/pw-browsers")));
        String home = System.getProperty("user.home");
        if (home != null && !home.isBlank()) {
            roots.add(Path.of(home, ".cache", "ms-playwright"));
        }
        for (Path root : roots) {
            try (var dirs = Files.newDirectoryStream(root, "chromium-*")) {
                for (Path dir : dirs) {
                    out.add(dir.resolve("chrome-linux64/chrome"));
                    out.add(dir.resolve("chrome-linux/chrome"));
                }
            } catch (IOException e) {
                // no Playwright browsers here
            }
        }
        return out;
    }

    /** The first executable candidate. */
    public static java.util.Optional<Path> detect() {
        return candidates().stream().filter(Files::isExecutable).findFirst();
    }

    /** A browser that may load anything. */
    public static CdpBrowser launch(Options options) throws IOException {
        return launch(options, url -> true);
    }

    /** A browser whose every request (any tab, frame or worker) {@code allowed} decides first. */
    public static CdpBrowser launch(Options options, java.util.function.Predicate<String> allowed) throws IOException {
        Path profile = Files.createTempDirectory("loopforge-browser-");
        List<String> argv = new ArrayList<>(List.of(
                options.binary().toString(),
                "--headless=new",
                "--disable-gpu",
                "--no-first-run",
                "--no-default-browser-check",
                "--disable-extensions",
                "--disable-background-networking",
                "--disable-sync",
                "--disable-dev-shm-usage",
                "--mute-audio",
                "--hide-scrollbars",
                // frames stay in their page's process, under its interception, as a second line
                "--disable-site-isolation-trials",
                "--disable-features=IsolateOrigins,site-per-process,Translate,MediaRouter",
                "--remote-debugging-port=0",
                "--remote-allow-origins=*",
                "--user-data-dir=" + profile));
        if (options.noSandbox()) {
            argv.add("--no-sandbox");
        }
        argv.add("about:blank");
        Process process = new ProcessBuilder(argv)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start();
        CdpBrowser browser = new CdpBrowser(process, profile, options.timeout(), allowed);
        try {
            String endpoint = browser.endpoint(options.timeout());
            browser.socket = HttpClient.newHttpClient()
                    .newWebSocketBuilder()
                    .connectTimeout(options.timeout())
                    .buildAsync(URI.create(endpoint), browser.new Listener())
                    .get(options.timeout().toMillis(), TimeUnit.MILLISECONDS);
            browser.listen(browser::guard);
            browser.send("Target.setAutoAttach", AUTO_ATTACH, null);
            return browser;
        } catch (Exception e) {
            browser.close();
            throw e instanceof IOException io ? io : new IOException("cannot start the browser: " + e.getMessage(), e);
        }
    }

    private static final Map<String, Object> AUTO_ATTACH =
            Map.of("autoAttach", true, "waitForDebuggerOnStart", true, "flatten", true);

    /** Intercepts every attached target before it runs, and decides each request it makes. */
    private void guard(JsonNode event) {
        String method = event.path("method").asString("");
        JsonNode params = event.path("params");
        switch (method) {
            case "Target.attachedToTarget" -> {
                String session = params.path("sessionId").asString("");
                String target = params.path("targetInfo").path("targetId").asString("");
                Thread.ofVirtual().name("browser-attach").start(() -> {
                    try {
                        send("Fetch.enable", Map.of("patterns", List.of(Map.of("urlPattern", "*"))), session);
                    } catch (IOException e) {
                        // a target without the Fetch domain loads through its page, which has it
                    }
                    try {
                        send("Target.setAutoAttach", AUTO_ATTACH, session);
                    } catch (IOException e) {
                        // nothing nested to attach
                    }
                    post("Runtime.runIfWaitingForDebugger", Map.of(), session);
                    sessions.computeIfAbsent(target, t -> new CompletableFuture<>())
                            .complete(session);
                });
            }
            case "Target.detachedFromTarget" ->
                sessions.remove(params.path("targetId").asString(""));
            case "Fetch.requestPaused" -> {
                String session = event.path("sessionId").asString("");
                String requestId = params.path("requestId").asString("");
                String url = params.path("request").path("url").asString("");
                boolean ok;
                try {
                    ok = allowed.test(url);
                } catch (RuntimeException e) {
                    ok = false;
                }
                if (ok) {
                    post("Fetch.continueRequest", Map.of("requestId", requestId), session);
                } else {
                    if (blocked.size() < 100) {
                        blocked.add(url);
                    }
                    post(
                            "Fetch.failRequest",
                            Map.of("requestId", requestId, "errorReason", "BlockedByClient"),
                            session);
                }
            }
            default -> {
                // not ours
            }
        }
    }

    /** The guarded session of {@code targetId}, once it is attached and intercepted. */
    public String session(String targetId) throws IOException {
        try {
            return sessions.computeIfAbsent(targetId, t -> new CompletableFuture<>())
                    .get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new IOException("the tab was not attached within " + timeout.toSeconds() + "s");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted");
        } catch (java.util.concurrent.ExecutionException e) {
            throw new IOException(e.getCause().getMessage(), e.getCause());
        }
    }

    /** Requests the policy refused (the first hundred). */
    public List<String> blocked() {
        return List.copyOf(blocked);
    }

    /** Reads the browser's stderr until it announces its DevTools endpoint; keeps draining after. */
    private String endpoint(Duration limit) throws IOException {
        CompletableFuture<String> found = new CompletableFuture<>();
        Thread.ofVirtual().name("browser-stderr").start(() -> {
            try (BufferedReader err =
                    new BufferedReader(new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                for (String line = err.readLine(); line != null; line = err.readLine()) {
                    Matcher m = LISTENING.matcher(line);
                    if (m.find()) {
                        found.complete(m.group(1));
                    }
                }
            } catch (IOException e) {
                // the process ended
            }
            found.completeExceptionally(new IOException("the browser exited before it was ready"));
        });
        try {
            return found.get(limit.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new IOException("the browser did not start within " + limit.toSeconds() + "s");
        } catch (Exception e) {
            throw new IOException("cannot start the browser: " + e.getMessage(), e);
        }
    }

    /** Sends a command (to a page when {@code sessionId} is set) and waits for its result. */
    public JsonNode send(String method, Map<String, Object> params, String sessionId) throws IOException {
        int id = ids.incrementAndGet();
        Map<String, Object> message = new java.util.LinkedHashMap<>();
        message.put("id", id);
        message.put("method", method);
        message.put("params", params == null ? Map.of() : params);
        if (sessionId != null) {
            message.put("sessionId", sessionId);
        }
        CompletableFuture<JsonNode> reply = new CompletableFuture<>();
        pending.put(id, reply);
        try {
            synchronized (this) {
                socket.sendText(Json.write(message), true).get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            }
            JsonNode answer = reply.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (answer.has("error")) {
                throw new IOException(
                        method + ": " + answer.path("error").path("message").asString("failed"));
            }
            return answer.path("result");
        } catch (TimeoutException e) {
            throw new IOException(method + " timed out");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted");
        } catch (java.util.concurrent.ExecutionException e) {
            throw new IOException(method + ": " + e.getCause().getMessage(), e.getCause());
        } finally {
            pending.remove(id);
        }
    }

    /** Sends without waiting for the result (event handlers must not block the socket). */
    public void post(String method, Map<String, Object> params, String sessionId) {
        Thread.ofVirtual().start(() -> {
            try {
                send(method, params, sessionId);
            } catch (IOException e) {
                // the page went away
            }
        });
    }

    public void listen(Consumer<JsonNode> listener) {
        listeners.add(listener);
    }

    public void unlisten(Consumer<JsonNode> listener) {
        listeners.remove(listener);
    }

    public Duration timeout() {
        return timeout;
    }

    /** The process runs and its DevTools connection is open. */
    public boolean alive() {
        WebSocket ws = socket;
        return process.isAlive() && ws != null && !ws.isInputClosed() && !ws.isOutputClosed();
    }

    private final class Listener implements WebSocket.Listener {
        private final StringBuilder partial = new StringBuilder();

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            partial.append(data);
            if (last) {
                String text = partial.toString();
                partial.setLength(0);
                try {
                    JsonNode message = Json.mapper().readTree(text);
                    if (message.has("id")) {
                        CompletableFuture<JsonNode> reply =
                                pending.get(message.path("id").asInt());
                        if (reply != null) {
                            reply.complete(message);
                        }
                    } else {
                        for (Consumer<JsonNode> listener : listeners) {
                            try {
                                listener.accept(message);
                            } catch (RuntimeException e) {
                                // one listener must not stop the others
                            }
                        }
                    }
                } catch (RuntimeException e) {
                    // not JSON: ignore
                }
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            pending.values().forEach(f -> f.completeExceptionally(error));
        }
    }

    @Override
    public void close() {
        if (socket != null) {
            socket.abort();
        }
        process.descendants().forEach(ProcessHandle::destroy);
        process.destroy();
        try {
            if (!process.waitFor(3, TimeUnit.SECONDS)) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        try (var walk = Files.walk(profile)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    // left for the temp cleaner
                }
            });
        } catch (IOException e) {
            // already gone
        }
    }
}
