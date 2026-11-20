package io.github.loopforge4j.server.browser;

import io.github.loopforge4j.core.runtime.browser.AgentBrowser;
import io.github.loopforge4j.core.runtime.browser.CdpBrowser;
import io.github.loopforge4j.server.config.LoopForgeProperties;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * The agent browsers open on this instance, one per run or conversation workspace (upstream run
 * browser): started when first used, closed when idle, at most {@code maxSessions} at a time.
 */
@Service
public class AgentBrowsers {

    private static final Logger log = LoggerFactory.getLogger(AgentBrowsers.class);

    /** Why a browser cannot be had; the message is meant for the caller. */
    public static final class Unavailable extends Exception {
        public Unavailable(String message) {
            super(message);
        }
    }

    private final LoopForgeProperties.Browser config;
    private final Clock clock;
    private final Map<String, AgentBrowser> open = new ConcurrentHashMap<>();
    private final Map<String, Object> locks = new ConcurrentHashMap<>();

    public AgentBrowsers(LoopForgeProperties properties, Clock clock) {
        this.config = properties.browser();
        this.clock = clock;
    }

    /** Agents and conversations get a browser on this deployment. */
    public boolean offered() {
        return config.agents() && config.options().isPresent();
    }

    /** Why there is no agent browser here, or empty when there is one. */
    public Optional<String> unavailable() {
        if (!config.enabled() || !config.agents()) {
            return Optional.of("本部署关闭了 Agent 浏览器（loopforge.browser.agents）");
        }
        if (config.options().isEmpty()) {
            return Optional.of("服务器上没有可用的 Chromium 或 Chrome（loopforge.browser.binary）");
        }
        return Optional.empty();
    }

    /** The open browser of {@code key}. */
    public Optional<AgentBrowser> find(String key) {
        AgentBrowser browser = open.get(key);
        if (browser != null && !browser.alive()) {
            close(key);
            return Optional.empty();
        }
        return Optional.ofNullable(browser);
    }

    /** The browser of {@code key}, started for {@code workspace} when none is open. */
    public AgentBrowser ensure(String key, Path workspace) throws Unavailable {
        Optional<AgentBrowser> existing = find(key);
        if (existing.isPresent()) {
            return existing.get();
        }
        Optional<String> reason = unavailable();
        if (reason.isPresent()) {
            throw new Unavailable(reason.get());
        }
        synchronized (locks.computeIfAbsent(key, k -> new Object())) {
            existing = find(key);
            if (existing.isPresent()) {
                return existing.get();
            }
            if (open.size() >= config.maxSessions()) {
                throw new Unavailable("同时打开的 Agent 浏览器已达上限（" + config.maxSessions()
                        + " 个，loopforge.browser.max-sessions），关闭不用的浏览器后再试");
            }
            CdpBrowser.Options options = config.options().orElseThrow();
            BrowserPolicy policy = new BrowserPolicy(workspace);
            try {
                AgentBrowser browser = AgentBrowser.launch(options, policy::allowed, clock);
                open.put(key, browser);
                log.info("agent browser started for {}", key);
                return browser;
            } catch (IOException e) {
                throw new Unavailable("Agent 浏览器启动失败：" + e.getMessage());
            }
        }
    }

    public boolean close(String key) {
        AgentBrowser browser = open.remove(key);
        locks.remove(key);
        if (browser == null) {
            return false;
        }
        browser.close();
        log.info("agent browser closed for {}", key);
        return true;
    }

    public int size() {
        return open.size();
    }

    /** Closes browsers nobody used for the idle timeout, and the ones that died. */
    @Scheduled(fixedDelayString = "PT1M", initialDelayString = "PT1M")
    public void reap() {
        Instant cutoff = clock.instant().minus(config.idleTimeout());
        for (Map.Entry<String, AgentBrowser> e : open.entrySet()) {
            if (!e.getValue().alive() || e.getValue().lastUsed().isBefore(cutoff)) {
                close(e.getKey());
            }
        }
    }

    @PreDestroy
    void closeAll() {
        open.keySet().forEach(this::close);
    }
}
