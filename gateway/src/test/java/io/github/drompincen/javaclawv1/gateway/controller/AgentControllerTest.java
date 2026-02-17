package io.github.drompincen.javaclawv1.gateway.controller;

import io.github.drompincen.javaclawv1.persistence.document.AgentDocument;
import io.github.drompincen.javaclawv1.persistence.repository.AgentRepository;
import io.github.drompincen.javaclawv1.protocol.api.AgentRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AgentControllerTest {

    @Mock private AgentRepository agentRepository;

    private AgentController controller;

    @BeforeEach
    void setUp() {
        controller = new AgentController(agentRepository);
    }

    @Test
    void listReturnsAllAgents() {
        AgentDocument agent = new AgentDocument();
        agent.setAgentId("controller");
        agent.setName("Controller");
        agent.setRole(AgentRole.CONTROLLER);
        agent.setEnabled(true);

        when(agentRepository.findAll()).thenReturn(List.of(agent));

        var result = controller.list();

        assertThat(result).hasSize(1);
    }

    @Test
    void getReturnsAgentWhenFound() {
        AgentDocument agent = new AgentDocument();
        agent.setAgentId("coder");
        agent.setName("Coder");
        agent.setRole(AgentRole.SPECIALIST);

        when(agentRepository.findById("coder")).thenReturn(Optional.of(agent));

        ResponseEntity<?> response = controller.get("coder");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void getReturns404WhenNotFound() {
        when(agentRepository.findById("bad")).thenReturn(Optional.empty());

        ResponseEntity<?> response = controller.get("bad");

        assertThat(response.getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void deleteRemovesAgent() {
        when(agentRepository.existsById("old")).thenReturn(true);

        ResponseEntity<?> response = controller.delete("old");

        assertThat(response.getStatusCode().value()).isEqualTo(204);
        verify(agentRepository).deleteById("old");
    }

    @Test
    void deleteReturns404WhenNotFound() {
        when(agentRepository.existsById("bad")).thenReturn(false);

        ResponseEntity<?> response = controller.delete("bad");

        assertThat(response.getStatusCode().value()).isEqualTo(404);
    }
}
