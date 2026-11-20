package io.github.loopforge4j.server.assistant;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Live events of conversations on this instance: assistant activity, new messages, turn status. */
@Component
class AssistantStreams {

    private final Map<String, List<SseEmitter>> subscribers = new ConcurrentHashMap<>();

    SseEmitter subscribe(String sessionId) {
        SseEmitter emitter = new SseEmitter(30 * 60 * 1000L);
        List<SseEmitter> list = subscribers.computeIfAbsent(sessionId, k -> new CopyOnWriteArrayList<>());
        list.add(emitter);
        Runnable remove = () -> list.remove(emitter);
        emitter.onCompletion(remove);
        emitter.onTimeout(remove);
        emitter.onError(e -> remove.run());
        return emitter;
    }

    void publish(String sessionId, String event, Object data) {
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
