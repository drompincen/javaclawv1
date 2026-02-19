package io.github.drompincen.javaclawv1.gateway.websocket;

import io.github.drompincen.javaclawv1.persistence.document.EventDocument;
import io.github.drompincen.javaclawv1.persistence.document.SessionDocument;
import io.github.drompincen.javaclawv1.persistence.repository.SessionRepository;
import io.github.drompincen.javaclawv1.persistence.stream.EventChangeStreamTailer;
import io.github.drompincen.javaclawv1.protocol.event.EventType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class JavaClawWebSocketHandlerTest {

    @Mock private EventChangeStreamTailer tailer;
    @Mock private SessionRepository sessionRepository;
    @Mock private WebSocketSession wsSession;
    @Mock private WebSocketSession wsSession2;

    private ObjectMapper objectMapper;
    private JavaClawWebSocketHandler handler;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        handler = new JavaClawWebSocketHandler(objectMapper, tailer, sessionRepository);
    }

    private EventDocument makeEvent(String sessionId) {
        EventDocument e = new EventDocument();
        e.setEventId("ev1");
        e.setSessionId(sessionId);
        e.setType(EventType.AGENT_STEP_STARTED);
        e.setPayload("test payload");
        e.setTimestamp(Instant.parse("2025-01-15T10:00:00Z"));
        e.setSeq(1);
        return e;
    }

    @Test
    void subscribeSessionSendsAck() throws Exception {
        handler.afterConnectionEstablished(wsSession);

        String msg = objectMapper.writeValueAsString(
                java.util.Map.of("type", "SUBSCRIBE_SESSION", "sessionId", "s1"));
        handler.handleTextMessage(wsSession, new TextMessage(msg));

        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(wsSession).sendMessage(captor.capture());
        String payload = captor.getValue().getPayload();
        assertThat(payload).contains("\"type\":\"SUBSCRIBED\"");
        assertThat(payload).contains("\"sessionId\":\"s1\"");
    }

    @Test
    void subscribeProjectSendsAck() throws Exception {
        handler.afterConnectionEstablished(wsSession);

        String msg = objectMapper.writeValueAsString(
                java.util.Map.of("type", "SUBSCRIBE_PROJECT", "projectId", "p1"));
        handler.handleTextMessage(wsSession, new TextMessage(msg));

        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(wsSession).sendMessage(captor.capture());
        String payload = captor.getValue().getPayload();
        assertThat(payload).contains("\"type\":\"SUBSCRIBED\"");
        assertThat(payload).contains("\"projectId\":\"p1\"");
    }

    @Test
    void onEventBroadcastsToSessionSubscribers() throws Exception {
        when(wsSession.isOpen()).thenReturn(true);
        handler.afterConnectionEstablished(wsSession);

        String msg = objectMapper.writeValueAsString(
                java.util.Map.of("type", "SUBSCRIBE_SESSION", "sessionId", "s1"));
        handler.handleTextMessage(wsSession, new TextMessage(msg));
        reset(wsSession);
        when(wsSession.isOpen()).thenReturn(true);

        handler.onEvent(makeEvent("s1"));

        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(wsSession).sendMessage(captor.capture());
        String payload = captor.getValue().getPayload();
        assertThat(payload).contains("\"type\":\"EVENT\"");
        assertThat(payload).contains("\"sessionId\":\"s1\"");
        assertThat(payload).contains("AGENT_STEP_STARTED");
    }

    @Test
    void onEventDoesNotBroadcastToUnsubscribedSession() throws Exception {
        handler.afterConnectionEstablished(wsSession);

        // Subscribe then unsubscribe
        String sub = objectMapper.writeValueAsString(
                java.util.Map.of("type", "SUBSCRIBE_SESSION", "sessionId", "s1"));
        handler.handleTextMessage(wsSession, new TextMessage(sub));
        String unsub = objectMapper.writeValueAsString(
                java.util.Map.of("type", "UNSUBSCRIBE", "sessionId", "s1"));
        handler.handleTextMessage(wsSession, new TextMessage(unsub));
        reset(wsSession);

        handler.onEvent(makeEvent("s1"));

        verify(wsSession, never()).sendMessage(any());
    }

    @Test
    void onEventBroadcastsToProjectSubscribers() throws Exception {
        when(wsSession.isOpen()).thenReturn(true);
        handler.afterConnectionEstablished(wsSession);

        String msg = objectMapper.writeValueAsString(
                java.util.Map.of("type", "SUBSCRIBE_PROJECT", "projectId", "p1"));
        handler.handleTextMessage(wsSession, new TextMessage(msg));
        reset(wsSession);
        when(wsSession.isOpen()).thenReturn(true);

        SessionDocument sess = new SessionDocument();
        sess.setSessionId("s1");
        sess.setProjectId("p1");
        when(sessionRepository.findById("s1")).thenReturn(Optional.of(sess));

        handler.onEvent(makeEvent("s1"));

        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(wsSession).sendMessage(captor.capture());
        String payload = captor.getValue().getPayload();
        assertThat(payload).contains("\"type\":\"EVENT\"");
    }

    @Test
    void onEventDoesNotBroadcastToProjectSubscriberWhenProjectMismatch() throws Exception {
        handler.afterConnectionEstablished(wsSession);

        String msg = objectMapper.writeValueAsString(
                java.util.Map.of("type", "SUBSCRIBE_PROJECT", "projectId", "p1"));
        handler.handleTextMessage(wsSession, new TextMessage(msg));
        reset(wsSession);

        SessionDocument sess = new SessionDocument();
        sess.setSessionId("s1");
        sess.setProjectId("p2");
        when(sessionRepository.findById("s1")).thenReturn(Optional.of(sess));

        handler.onEvent(makeEvent("s1"));

        verify(wsSession, never()).sendMessage(any());
    }

    @Test
    void onEventSkipsClosedSessions() throws Exception {
        when(wsSession.isOpen()).thenReturn(true);
        handler.afterConnectionEstablished(wsSession);

        String msg = objectMapper.writeValueAsString(
                java.util.Map.of("type", "SUBSCRIBE_SESSION", "sessionId", "s1"));
        handler.handleTextMessage(wsSession, new TextMessage(msg));
        reset(wsSession);
        when(wsSession.isOpen()).thenReturn(false);

        handler.onEvent(makeEvent("s1"));

        verify(wsSession, never()).sendMessage(any());
    }

    @Test
    void afterConnectionClosedCleansUpSubscriptions() throws Exception {
        handler.afterConnectionEstablished(wsSession);

        handler.handleTextMessage(wsSession, new TextMessage(
                objectMapper.writeValueAsString(
                        java.util.Map.of("type", "SUBSCRIBE_SESSION", "sessionId", "s1"))));
        handler.handleTextMessage(wsSession, new TextMessage(
                objectMapper.writeValueAsString(
                        java.util.Map.of("type", "SUBSCRIBE_PROJECT", "projectId", "p1"))));
        reset(wsSession);

        handler.afterConnectionClosed(wsSession, CloseStatus.NORMAL);

        // Events should not reach the closed session
        handler.onEvent(makeEvent("s1"));
        verify(wsSession, never()).sendMessage(any());
    }

    @Test
    void onEventHandlesNullPayload() throws Exception {
        when(wsSession.isOpen()).thenReturn(true);
        handler.afterConnectionEstablished(wsSession);

        String msg = objectMapper.writeValueAsString(
                java.util.Map.of("type", "SUBSCRIBE_SESSION", "sessionId", "s1"));
        handler.handleTextMessage(wsSession, new TextMessage(msg));
        reset(wsSession);
        when(wsSession.isOpen()).thenReturn(true);

        EventDocument event = makeEvent("s1");
        event.setPayload(null);
        handler.onEvent(event);

        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(wsSession).sendMessage(captor.capture());
        String payload = captor.getValue().getPayload();
        assertThat(payload).contains("\"payload\":\"\"");
    }

    @Test
    void bothSessionAndProjectSubscribersReceiveEvent() throws Exception {
        when(wsSession.isOpen()).thenReturn(true);
        when(wsSession2.isOpen()).thenReturn(true);
        handler.afterConnectionEstablished(wsSession);
        handler.afterConnectionEstablished(wsSession2);

        handler.handleTextMessage(wsSession, new TextMessage(
                objectMapper.writeValueAsString(
                        java.util.Map.of("type", "SUBSCRIBE_SESSION", "sessionId", "s1"))));
        handler.handleTextMessage(wsSession2, new TextMessage(
                objectMapper.writeValueAsString(
                        java.util.Map.of("type", "SUBSCRIBE_PROJECT", "projectId", "p1"))));
        reset(wsSession, wsSession2);
        when(wsSession.isOpen()).thenReturn(true);
        when(wsSession2.isOpen()).thenReturn(true);

        SessionDocument sess = new SessionDocument();
        sess.setSessionId("s1");
        sess.setProjectId("p1");
        when(sessionRepository.findById("s1")).thenReturn(Optional.of(sess));

        handler.onEvent(makeEvent("s1"));

        verify(wsSession).sendMessage(any(TextMessage.class));
        verify(wsSession2).sendMessage(any(TextMessage.class));
    }
}
