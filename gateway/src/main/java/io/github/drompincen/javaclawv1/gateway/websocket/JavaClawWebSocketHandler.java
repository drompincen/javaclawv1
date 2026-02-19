package io.github.drompincen.javaclawv1.gateway.websocket;

import io.github.drompincen.javaclawv1.persistence.document.EventDocument;
import io.github.drompincen.javaclawv1.persistence.repository.SessionRepository;
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
    private final SessionRepository sessionRepository;
    private final Map<String, Set<WebSocketSession>> sessionSubscriptions = new ConcurrentHashMap<>();
    private final Map<String, Set<WebSocketSession>> projectSubscriptions = new ConcurrentHashMap<>();
    private final Set<WebSocketSession> allSessions = new CopyOnWriteArraySet<>();

    public JavaClawWebSocketHandler(ObjectMapper objectMapper, EventChangeStreamTailer tailer,
                                     SessionRepository sessionRepository) {
        this.objectMapper = objectMapper;
        this.tailer = tailer;
        this.sessionRepository = sessionRepository;
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
        projectSubscriptions.values().forEach(set -> set.remove(session));
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
        } else if ("SUBSCRIBE_PROJECT".equals(type)) {
            String projectId = node.path("projectId").asText();
            projectSubscriptions.computeIfAbsent(projectId, k -> new CopyOnWriteArraySet<>()).add(session);
            session.sendMessage(new TextMessage(
                    objectMapper.writeValueAsString(Map.of("type", "SUBSCRIBED", "projectId", projectId))));
        } else if ("UNSUBSCRIBE".equals(type)) {
            var set = sessionSubscriptions.get(sessionId);
            if (set != null) set.remove(session);
        }
    }

    @Override
    public void onEvent(EventDocument event) {
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

            // Broadcast to session subscribers
            var sessionSubs = sessionSubscriptions.get(event.getSessionId());
            if (sessionSubs != null) {
                for (var ws : sessionSubs) {
                    if (ws.isOpen()) {
                        try { ws.sendMessage(tm); } catch (IOException ignored) {}
                    }
                }
            }

            // Broadcast to project subscribers
            sessionRepository.findById(event.getSessionId()).ifPresent(sess -> {
                if (sess.getProjectId() != null) {
                    var projectSubs = projectSubscriptions.get(sess.getProjectId());
                    if (projectSubs != null) {
                        for (var ws : projectSubs) {
                            if (ws.isOpen()) {
                                try { ws.sendMessage(tm); } catch (IOException ignored) {}
                            }
                        }
                    }
                }
            });
        } catch (Exception e) {
            log.error("Error broadcasting event", e);
        }
    }

    @Override
    public void onError(Throwable t) {
        log.error("Event stream error in WebSocket handler", t);
    }
}
