package io.github.drompincen.javaclawv1.gateway.websocket;

import io.github.drompincen.javaclawv1.persistence.document.EventDocument;
import io.github.drompincen.javaclawv1.persistence.stream.EventChangeStreamTailer;
import io.github.drompincen.javaclawv1.persistence.stream.EventStreamListener;
import io.github.drompincen.javaclawv1.protocol.ws.WsMessage;
import io.github.drompincen.javaclawv1.protocol.ws.WsMessageType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

@Component
public class JavaClawWebSocketHandler extends TextWebSocketHandler implements EventStreamListener {

    private static final Logger log = LoggerFactory.getLogger(JavaClawWebSocketHandler.class);

    private final ObjectMapper objectMapper;
    private final EventChangeStreamTailer tailer;
    private final Map<String, Set<WebSocketSession>> sessionSubscriptions = new ConcurrentHashMap<>();
    private final Set<WebSocketSession> allSessions = new CopyOnWriteArraySet<>();

    public JavaClawWebSocketHandler(ObjectMapper objectMapper, EventChangeStreamTailer tailer) {
        this.objectMapper = objectMapper;
        this.tailer = tailer;
    }

    @PostConstruct
    public void init() {
        tailer.addListener(this);
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        allSessions.add(session);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        allSessions.remove(session);
        sessionSubscriptions.values().forEach(set -> set.remove(session));
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        var node = objectMapper.readTree(message.getPayload());
        String type = node.path("type").asText();
        String sessionId = node.path("sessionId").asText();

        if ("SUBSCRIBE_SESSION".equals(type)) {
            sessionSubscriptions.computeIfAbsent(sessionId, k -> new CopyOnWriteArraySet<>()).add(session);
            session.sendMessage(new TextMessage(
                    objectMapper.writeValueAsString(Map.of("type", "SUBSCRIBED", "sessionId", sessionId))));
        } else if ("UNSUBSCRIBE".equals(type)) {
            var set = sessionSubscriptions.get(sessionId);
            if (set != null) set.remove(session);
        }
    }

    @Override
    public void onEvent(EventDocument event) {
        var subscribers = sessionSubscriptions.get(event.getSessionId());
        if (subscribers == null || subscribers.isEmpty()) return;

        try {
            String json = objectMapper.writeValueAsString(Map.of(
                    "type", "EVENT",
                    "sessionId", event.getSessionId(),
                    "payload", Map.of(
                            "eventId", event.getEventId(),
                            "type", event.getType(),
                            "payload", event.getPayload() != null ? event.getPayload() : "",
                            "timestamp", event.getTimestamp().toString(),
                            "seq", event.getSeq())));
            TextMessage tm = new TextMessage(json);
            for (var ws : subscribers) {
                if (ws.isOpen()) {
                    try { ws.sendMessage(tm); } catch (IOException ignored) {}
                }
            }
        } catch (Exception e) {
            log.error("Error broadcasting event", e);
        }
    }

    @Override
    public void onError(Throwable t) {
        log.error("Event stream error in WebSocket handler", t);
    }
}
