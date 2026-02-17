package io.github.drompincen.javaclawv1.gateway.controller;

import io.github.drompincen.javaclawv1.persistence.document.MessageDocument;
import io.github.drompincen.javaclawv1.persistence.document.SessionDocument;
import io.github.drompincen.javaclawv1.persistence.repository.MessageRepository;
import io.github.drompincen.javaclawv1.persistence.repository.SessionRepository;
import io.github.drompincen.javaclawv1.protocol.api.ModelConfig;
import io.github.drompincen.javaclawv1.protocol.api.SessionStatus;
import io.github.drompincen.javaclawv1.protocol.api.ToolPolicy;
import io.github.drompincen.javaclawv1.runtime.agent.AgentLoop;
import io.github.drompincen.javaclawv1.runtime.agent.EventService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SessionControllerTest {

    @Mock private SessionRepository sessionRepository;
    @Mock private MessageRepository messageRepository;
    @Mock private AgentLoop agentLoop;
    @Mock private EventService eventService;

    private SessionController controller;

    @BeforeEach
    void setUp() {
        controller = new SessionController(sessionRepository, messageRepository, agentLoop, eventService);
    }

    @Test
    void createSessionReturnsWithIdleStatus() {
        when(sessionRepository.save(any(SessionDocument.class))).thenAnswer(inv -> inv.getArgument(0));

        var req = new io.github.drompincen.javaclawv1.protocol.api.CreateSessionRequest(null, null, null);
        ResponseEntity<?> response = controller.create(req);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isNotNull();
    }

    @Test
    void getSessionReturns404WhenNotFound() {
        when(sessionRepository.findById("bad")).thenReturn(Optional.empty());

        ResponseEntity<?> response = controller.get("bad");

        assertThat(response.getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void getSessionReturnsSessionWhenFound() {
        SessionDocument doc = new SessionDocument();
        doc.setSessionId("s1");
        doc.setStatus(SessionStatus.IDLE);
        doc.setCreatedAt(Instant.now());
        doc.setUpdatedAt(Instant.now());
        doc.setModelConfig(ModelConfig.defaults());
        doc.setToolPolicy(ToolPolicy.allowAll());
        when(sessionRepository.findById("s1")).thenReturn(Optional.of(doc));

        ResponseEntity<?> response = controller.get("s1");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void listSessionsReturnsAll() {
        when(sessionRepository.findAllByOrderByUpdatedAtDesc()).thenReturn(List.of());

        var result = controller.list();

        assertThat(result).isEmpty();
    }

    @Test
    void runSessionStartsAgentLoop() {
        SessionDocument doc = new SessionDocument();
        doc.setSessionId("s1");
        when(sessionRepository.findById("s1")).thenReturn(Optional.of(doc));

        ResponseEntity<?> response = controller.run("s1");

        assertThat(response.getStatusCode().value()).isEqualTo(202);
        verify(agentLoop).startAsync("s1");
    }

    @Test
    void runSessionReturns404WhenNotFound() {
        when(sessionRepository.findById("bad")).thenReturn(Optional.empty());

        ResponseEntity<?> response = controller.run("bad");

        assertThat(response.getStatusCode().value()).isEqualTo(404);
    }
}
