package io.github.drompincen.javaclawv1.runtime.scheduler;

import io.github.drompincen.javaclawv1.persistence.document.FutureExecutionDocument;
import io.github.drompincen.javaclawv1.persistence.document.SessionDocument;
import io.github.drompincen.javaclawv1.persistence.repository.FutureExecutionRepository;
import io.github.drompincen.javaclawv1.persistence.repository.MessageRepository;
import io.github.drompincen.javaclawv1.persistence.repository.PastExecutionRepository;
import io.github.drompincen.javaclawv1.persistence.repository.SessionRepository;
import io.github.drompincen.javaclawv1.protocol.api.ExecStatus;
import io.github.drompincen.javaclawv1.runtime.agent.AgentLoop;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ExecutionEngineServiceTest {

    @Mock private FutureExecutionRepository futureExecutionRepository;
    @Mock private PastExecutionRepository pastExecutionRepository;
    @Mock private SessionRepository sessionRepository;
    @Mock private MessageRepository messageRepository;
    @Mock private AgentLoop agentLoop;
    @Mock private LeaseHeartbeatService leaseHeartbeatService;
    @Captor private ArgumentCaptor<FutureExecutionDocument> execCaptor;

    private ExecutionEngineService service;

    @BeforeEach
    void setUp() {
        service = new ExecutionEngineService(
                futureExecutionRepository, pastExecutionRepository,
                sessionRepository, messageRepository,
                agentLoop, leaseHeartbeatService);
    }

    // ------------------------------------------------------------------
    // Stale lease recovery
    // ------------------------------------------------------------------

    @Test
    void recoverStaleLeases_resetsToReady_whenNoAttempts() {
        FutureExecutionDocument stale = makeExec("exec-stale-1", "agent-a", ExecStatus.RUNNING);
        stale.setAttempt(0);
        stale.setLeaseUntil(Instant.now().minusSeconds(60));

        when(futureExecutionRepository.findByLeaseUntilLessThanAndExecStatusIn(any(), any()))
                .thenReturn(List.of(stale));
        // No READY items to execute
        when(futureExecutionRepository.findByExecStatusAndScheduledAtLessThanEqualOrderByPriorityDescScheduledAtAsc(
                any(), any())).thenReturn(List.of());

        service.pollAndExecute();

        verify(futureExecutionRepository).save(execCaptor.capture());
        assertThat(execCaptor.getValue().getExecStatus()).isEqualTo(ExecStatus.READY);
        assertThat(execCaptor.getValue().getLockOwner()).isNull();
        assertThat(execCaptor.getValue().getLeaseUntil()).isNull();
    }

    @Test
    void recoverStaleLeases_setsFailedRetryable_whenHasAttempts() {
        FutureExecutionDocument stale = makeExec("exec-stale-2", "agent-b", ExecStatus.RUNNING);
        stale.setAttempt(1);
        stale.setLeaseUntil(Instant.now().minusSeconds(60));

        when(futureExecutionRepository.findByLeaseUntilLessThanAndExecStatusIn(any(), any()))
                .thenReturn(List.of(stale));
        when(futureExecutionRepository.findByExecStatusAndScheduledAtLessThanEqualOrderByPriorityDescScheduledAtAsc(
                any(), any())).thenReturn(List.of());

        service.pollAndExecute();

        verify(futureExecutionRepository).save(execCaptor.capture());
        assertThat(execCaptor.getValue().getExecStatus()).isEqualTo(ExecStatus.FAILED_RETRYABLE);
    }

    // ------------------------------------------------------------------
    // Concurrency limit
    // ------------------------------------------------------------------

    @Test
    void pollAndExecute_skipsAgent_whenAlreadyRunning() {
        FutureExecutionDocument readyItem = makeExec("exec-ready", "agent-busy", ExecStatus.READY);
        readyItem.setScheduledAt(Instant.now().minusSeconds(10));

        FutureExecutionDocument runningItem = makeExec("exec-running", "agent-busy", ExecStatus.RUNNING);

        when(futureExecutionRepository.findByLeaseUntilLessThanAndExecStatusIn(any(), any()))
                .thenReturn(List.of());
        when(futureExecutionRepository.findByExecStatusAndScheduledAtLessThanEqualOrderByPriorityDescScheduledAtAsc(
                eq(ExecStatus.READY), any()))
                .thenReturn(List.of(readyItem));
        when(futureExecutionRepository.findByAgentIdAndExecStatus("agent-busy", ExecStatus.RUNNING))
                .thenReturn(List.of(runningItem));

        service.pollAndExecute();

        // Should not try to claim — already at max concurrency
        verify(futureExecutionRepository, never()).findById("exec-ready");
    }

    // ------------------------------------------------------------------
    // Claiming
    // ------------------------------------------------------------------

    @Test
    void pollAndExecute_claimsReadyItem_whenNoConcurrencyConflict() {
        FutureExecutionDocument readyItem = makeExec("exec-claim", "agent-free", ExecStatus.READY);
        readyItem.setScheduledAt(Instant.now().minusSeconds(10));

        when(futureExecutionRepository.findByLeaseUntilLessThanAndExecStatusIn(any(), any()))
                .thenReturn(List.of());
        when(futureExecutionRepository.findByExecStatusAndScheduledAtLessThanEqualOrderByPriorityDescScheduledAtAsc(
                eq(ExecStatus.READY), any()))
                .thenReturn(List.of(readyItem));
        when(futureExecutionRepository.findByAgentIdAndExecStatus("agent-free", ExecStatus.RUNNING))
                .thenReturn(List.of());
        when(futureExecutionRepository.findById("exec-claim"))
                .thenReturn(Optional.of(readyItem));
        when(futureExecutionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.pollAndExecute();

        // Verify the claim read + save happened
        verify(futureExecutionRepository).findById("exec-claim");
        verify(futureExecutionRepository, atLeastOnce()).save(any());
        // After claim, the item's lockOwner must be set
        assertThat(readyItem.getLockOwner()).isNotNull();
    }

    @Test
    void pollAndExecute_doesNotClaim_whenAlreadyTakenByAnother() {
        FutureExecutionDocument readyItem = makeExec("exec-race", "agent-race", ExecStatus.READY);
        readyItem.setScheduledAt(Instant.now().minusSeconds(10));

        // By the time we findById, someone else has claimed it
        FutureExecutionDocument alreadyClaimed = makeExec("exec-race", "agent-race", ExecStatus.PENDING);

        when(futureExecutionRepository.findByLeaseUntilLessThanAndExecStatusIn(any(), any()))
                .thenReturn(List.of());
        when(futureExecutionRepository.findByExecStatusAndScheduledAtLessThanEqualOrderByPriorityDescScheduledAtAsc(
                eq(ExecStatus.READY), any()))
                .thenReturn(List.of(readyItem));
        when(futureExecutionRepository.findByAgentIdAndExecStatus("agent-race", ExecStatus.RUNNING))
                .thenReturn(List.of());
        when(futureExecutionRepository.findById("exec-race"))
                .thenReturn(Optional.of(alreadyClaimed));

        service.pollAndExecute();

        // Should not save (claim rejected because status != READY)
        verify(futureExecutionRepository, never()).save(any());
    }

    // ------------------------------------------------------------------
    // No work to do
    // ------------------------------------------------------------------

    @Test
    void pollAndExecute_noReadyItems_doesNothing() {
        when(futureExecutionRepository.findByLeaseUntilLessThanAndExecStatusIn(any(), any()))
                .thenReturn(List.of());
        when(futureExecutionRepository.findByExecStatusAndScheduledAtLessThanEqualOrderByPriorityDescScheduledAtAsc(
                any(), any())).thenReturn(List.of());

        service.pollAndExecute();

        verify(agentLoop, never()).startAsync(any());
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static FutureExecutionDocument makeExec(String execId, String agentId, ExecStatus status) {
        FutureExecutionDocument doc = new FutureExecutionDocument();
        doc.setExecutionId(execId);
        doc.setAgentId(agentId);
        doc.setExecStatus(status);
        doc.setMaxAttempts(3);
        doc.setRetryBackoffMs(60000);
        doc.setCreatedAt(Instant.now());
        doc.setLastUpdatedAt(Instant.now());
        return doc;
    }
}
