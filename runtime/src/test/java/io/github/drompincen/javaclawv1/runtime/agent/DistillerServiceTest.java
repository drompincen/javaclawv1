package io.github.drompincen.javaclawv1.runtime.agent;

import io.github.drompincen.javaclawv1.persistence.document.MemoryDocument;
import io.github.drompincen.javaclawv1.persistence.document.MessageDocument;
import io.github.drompincen.javaclawv1.persistence.document.SessionDocument;
import io.github.drompincen.javaclawv1.persistence.document.ThreadDocument;
import io.github.drompincen.javaclawv1.persistence.repository.MemoryRepository;
import io.github.drompincen.javaclawv1.persistence.repository.MessageRepository;
import io.github.drompincen.javaclawv1.persistence.repository.SessionRepository;
import io.github.drompincen.javaclawv1.persistence.repository.ThreadRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DistillerServiceTest {

    @Mock private SessionRepository sessionRepository;
    @Mock private ThreadRepository threadRepository;
    @Mock private MessageRepository messageRepository;
    @Mock private MemoryRepository memoryRepository;
    @Mock private EventService eventService;

    private DistillerService distillerService;

    @BeforeEach
    void setUp() {
        distillerService = new DistillerService(
                sessionRepository, threadRepository, messageRepository, memoryRepository, eventService);
        when(memoryRepository.save(any(MemoryDocument.class))).thenAnswer(inv -> inv.getArgument(0));
        when(threadRepository.save(any(ThreadDocument.class))).thenAnswer(inv -> inv.getArgument(0));
        when(eventService.emit(anyString(), any(), any())).thenReturn(null);
    }

    @Test
    void distillThread_writesContentBackToThread() throws Exception {
        ThreadDocument thread = new ThreadDocument();
        thread.setThreadId("thread-1");
        thread.setProjectIds(List.of("proj-1"));
        thread.setTitle("Evidence Service Design");
        thread.setDecisions(new ArrayList<>());
        thread.setActions(new ArrayList<>());

        when(threadRepository.findById("thread-1")).thenReturn(Optional.of(thread));
        when(messageRepository.findBySessionIdOrderBySeqAsc("thread-1")).thenReturn(List.of());

        // Call distillThread directly (synchronous for testing)
        // We need to invoke the private method via the public async wrapper and wait
        distillerService.distillThread("thread-1");

        // Give the executor thread time to complete
        Thread.sleep(500);

        ArgumentCaptor<ThreadDocument> threadCaptor = ArgumentCaptor.forClass(ThreadDocument.class);
        verify(threadRepository, atLeastOnce()).save(threadCaptor.capture());
        ThreadDocument saved = threadCaptor.getValue();
        assertThat(saved.getContent()).isNotNull();
        assertThat(saved.getContent()).contains("Evidence Service Design");
    }

    @Test
    void distillThread_setsExpiresAtOnMemory() throws Exception {
        ThreadDocument thread = new ThreadDocument();
        thread.setThreadId("thread-1");
        thread.setProjectIds(List.of("proj-1"));
        thread.setTitle("Test Thread");
        thread.setDecisions(new ArrayList<>());
        thread.setActions(new ArrayList<>());

        when(threadRepository.findById("thread-1")).thenReturn(Optional.of(thread));
        when(messageRepository.findBySessionIdOrderBySeqAsc("thread-1")).thenReturn(List.of());

        distillerService.distillThread("thread-1");
        Thread.sleep(500);

        ArgumentCaptor<MemoryDocument> memCaptor = ArgumentCaptor.forClass(MemoryDocument.class);
        verify(memoryRepository).save(memCaptor.capture());
        MemoryDocument mem = memCaptor.getValue();
        assertThat(mem.getExpiresAt()).isNotNull();
        assertThat(mem.getExpiresAt()).isAfter(Instant.now());
    }

    @Test
    void distillSession_sets24hTTLOnMemory() throws Exception {
        SessionDocument session = new SessionDocument();
        session.setSessionId("session-1");
        session.setProjectId("proj-1");

        MessageDocument msg1 = new MessageDocument();
        msg1.setRole("user");
        msg1.setContent("What is the plan?");

        MessageDocument msg2 = new MessageDocument();
        msg2.setRole("assistant");
        msg2.setContent("The plan is to build the evidence service.");

        when(sessionRepository.findById("session-1")).thenReturn(Optional.of(session));
        when(messageRepository.findBySessionIdOrderBySeqAsc("session-1")).thenReturn(List.of(msg1, msg2));

        distillerService.distillAsync("session-1");
        Thread.sleep(500);

        ArgumentCaptor<MemoryDocument> memCaptor = ArgumentCaptor.forClass(MemoryDocument.class);
        verify(memoryRepository).save(memCaptor.capture());
        MemoryDocument mem = memCaptor.getValue();
        assertThat(mem.getScope()).isEqualTo(MemoryDocument.MemoryScope.SESSION);
        assertThat(mem.getExpiresAt()).isNotNull();
        // Should expire roughly 24h from now
        assertThat(mem.getExpiresAt()).isBefore(Instant.now().plusSeconds(86400 + 60));
        assertThat(mem.getExpiresAt()).isAfter(Instant.now().plusSeconds(86400 - 60));
    }
}
