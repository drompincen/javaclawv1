package io.github.drompincen.javaclawv1.runtime.agent;

import io.github.drompincen.javaclawv1.persistence.document.ProjectDocument;
import io.github.drompincen.javaclawv1.persistence.document.SessionDocument;
import io.github.drompincen.javaclawv1.persistence.document.ThreadDocument;
import io.github.drompincen.javaclawv1.persistence.repository.*;
import io.github.drompincen.javaclawv1.protocol.api.ExtractionRequest;
import io.github.drompincen.javaclawv1.protocol.api.ExtractionResponse;
import io.github.drompincen.javaclawv1.protocol.api.ExtractionType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.quality.Strictness;
import org.mockito.junit.jupiter.MockitoSettings;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ExtractionServiceTest {

    @Mock private ProjectRepository projectRepository;
    @Mock private ThreadRepository threadRepository;
    @Mock private SessionRepository sessionRepository;
    @Mock private MessageRepository messageRepository;
    @Mock private EventService eventService;
    @Mock private AgentLoop agentLoop;

    private ExtractionService extractionService;

    @BeforeEach
    void setUp() {
        extractionService = new ExtractionService(
                projectRepository, threadRepository, sessionRepository,
                messageRepository, eventService, agentLoop);

        // Default: project exists
        ProjectDocument project = new ProjectDocument();
        project.setProjectId("proj-1");
        when(projectRepository.findById("proj-1")).thenReturn(Optional.of(project));

        // Default: save returns argument
        when(sessionRepository.save(any(SessionDocument.class))).thenAnswer(inv -> inv.getArgument(0));
        when(messageRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(threadRepository.save(any(ThreadDocument.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void throwsForMissingProject() {
        when(projectRepository.findById("bad")).thenReturn(Optional.empty());
        ExtractionRequest req = new ExtractionRequest("bad", null, null, false);
        assertThatThrownBy(() -> extractionService.startExtraction(req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Project not found");
    }

    @Test
    void returnsCompletedWhenNoThreads() {
        when(threadRepository.findByProjectIdsOrderByUpdatedAtDesc("proj-1")).thenReturn(List.of());

        ExtractionRequest req = new ExtractionRequest("proj-1", null, null, false);
        ExtractionResponse response = extractionService.startExtraction(req);

        assertThat(response.status()).isEqualTo("COMPLETED");
        assertThat(response.summary()).isNotNull();
        assertThat(response.summary().threadsProcessed()).isEqualTo(0);
        verify(agentLoop, never()).startAsync(any());
    }

    @Test
    void startsExtractionForProjectThreads() {
        ThreadDocument thread1 = new ThreadDocument();
        thread1.setThreadId("t1");
        ThreadDocument thread2 = new ThreadDocument();
        thread2.setThreadId("t2");

        when(threadRepository.findByProjectIdsOrderByUpdatedAtDesc("proj-1"))
                .thenReturn(List.of(thread1, thread2));
        when(threadRepository.findById("t1")).thenReturn(Optional.of(thread1));
        when(threadRepository.findById("t2")).thenReturn(Optional.of(thread2));

        ExtractionRequest req = new ExtractionRequest("proj-1", null, Set.of(ExtractionType.ALL), false);
        ExtractionResponse response = extractionService.startExtraction(req);

        assertThat(response.status()).isEqualTo("QUEUED");
        assertThat(response.extractionId()).isNotBlank();
        assertThat(response.sessionId()).isNotBlank();

        // Verify session was created
        ArgumentCaptor<SessionDocument> sessionCaptor = ArgumentCaptor.forClass(SessionDocument.class);
        verify(sessionRepository).save(sessionCaptor.capture());
        assertThat(sessionCaptor.getValue().getProjectId()).isEqualTo("proj-1");
        assertThat(sessionCaptor.getValue().getMetadata()).containsEntry("type", "extraction");

        // Verify agent loop was started
        verify(agentLoop).startAsync(any());

        // Verify thread extraction tracking was updated
        verify(threadRepository, atLeastOnce()).save(any(ThreadDocument.class));
    }

    @Test
    void dryRunDoesNotStartAgentLoop() {
        ThreadDocument thread = new ThreadDocument();
        thread.setThreadId("t1");
        when(threadRepository.findByProjectIdsOrderByUpdatedAtDesc("proj-1"))
                .thenReturn(List.of(thread));
        when(threadRepository.findById("t1")).thenReturn(Optional.of(thread));

        ExtractionRequest req = new ExtractionRequest("proj-1", null, null, true);
        ExtractionResponse response = extractionService.startExtraction(req);

        assertThat(response.status()).isEqualTo("DRY_RUN");
        verify(agentLoop, never()).startAsync(any());
    }

    @Test
    void usesSpecificThreadIdsWhenProvided() {
        ThreadDocument thread = new ThreadDocument();
        thread.setThreadId("specific-thread");
        when(threadRepository.findById("specific-thread")).thenReturn(Optional.of(thread));

        ExtractionRequest req = new ExtractionRequest("proj-1", List.of("specific-thread"), null, false);
        ExtractionResponse response = extractionService.startExtraction(req);

        assertThat(response.status()).isEqualTo("QUEUED");
        // Should NOT query all project threads
        verify(threadRepository, never()).findByProjectIdsOrderByUpdatedAtDesc(any());
        verify(agentLoop).startAsync(any());
    }
}
