package io.github.loopforge4j.server.assistant;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Live events on this instance, keyed by topic: conversations (assistant activity, new messages,
 * turn status) and requirement clarifications ({@code clarify:<requirement id>}).
 */
@Component
public class AssistantStreams implements SmartLifecycle {

    private final Map<String, List<SseEmitter>> subscribers = new ConcurrentHashMap<>();
    private volatile boolean running;

    public SseEmitter subscribe(String sessionId) {
        SseEmitter emitter = new SseEmitter(30 * 60 * 1000L);
        List<SseEmitter> list = subscribers.computeIfAbsent(sessionId, k -> new CopyOnWriteArrayList<>());
        list.add(emitter);
        Runnable remove = () -> list.remove(emitter);
        emitter.onCompletion(remove);
        emitter.onTimeout(remove);
        emitter.onError(e -> remove.run());
        try {
            // commits the response at once: clients (and proxies) know the subscription is live
            emitter.send(SseEmitter.event().comment("subscribed"));
        } catch (IOException | IllegalStateException e) {
            list.remove(emitter);
        }
        return emitter;
    }

    /**
     * Completes every stream before the web server's graceful shutdown starts (this lifecycle has a
     * higher phase): open streams would otherwise hold shutdown until its timeout.
     */
    @Override
    public void stop() {
        running = false;
        subscribers.values().forEach(list -> {
            for (SseEmitter emitter : List.copyOf(list)) {
                list.remove(emitter);
                try {
                    emitter.complete();
                } catch (IllegalStateException e) {
                    // already completed
                }
            }
        });
    }

    @Override
    public void start() {
        running = true;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    public void publish(String sessionId, String event, Object data) {
        List<SseEmitter> list = subscribers.get(sessionId);
        if (list == null) {
            return;
        }
        for (SseEmitter emitter : list) {
            try {
                emitter.send(SseEmitter.event().name(event).data(data));
            } catch (IOException | IllegalStateException e) {
                list.remove(emitter);
            }
        }
    }
}
