package io.github.drompincen.javaclawv1.runtime.scheduler;

import io.github.drompincen.javaclawv1.persistence.document.AgentScheduleDocument;
import io.github.drompincen.javaclawv1.persistence.document.FutureExecutionDocument;
import io.github.drompincen.javaclawv1.persistence.repository.AgentScheduleRepository;
import io.github.drompincen.javaclawv1.persistence.repository.FutureExecutionRepository;
import io.github.drompincen.javaclawv1.protocol.api.ExecStatus;
import io.github.drompincen.javaclawv1.protocol.api.ScheduleType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SchedulePlannerServiceTest {

    @Mock private AgentScheduleRepository scheduleRepository;
    @Mock private FutureExecutionRepository futureExecutionRepository;
    @Captor private ArgumentCaptor<FutureExecutionDocument> execCaptor;

    private SchedulePlannerService service;

    @BeforeEach
    void setUp() {
        service = new SchedulePlannerService(scheduleRepository, futureExecutionRepository);
        when(futureExecutionRepository.existsByIdempotencyKey(anyString())).thenReturn(false);
        when(futureExecutionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    // ------------------------------------------------------------------
    // Past-slot skipping (the bug we fixed)
    // ------------------------------------------------------------------

    @Test
    void pastSlots_areSkipped_nothingSaved() {
        // FIXED_TIMES at 01:00 UTC — guaranteed past
        AgentScheduleDocument fixedPast = makeSchedule("s1", "agent-a",
                ScheduleType.FIXED_TIMES, null, List.of("01:00"), null);
        service.generateFutureExecutions(fixedPast);
        verify(futureExecutionRepository, never()).save(any());

        // CRON for 00:01 every day — also always past
        AgentScheduleDocument cronPast = makeSchedule("s2", "agent-b",
                ScheduleType.CRON, "0 1 0 * * *", null, null);
        service.generateFutureExecutions(cronPast);
        verify(futureExecutionRepository, never()).save(any());

        // INTERVAL with 1440-min (once a day at midnight) — past
        AgentScheduleDocument intervalPast = makeSchedule("s3", "agent-c",
                ScheduleType.INTERVAL, null, null, 1440);
        service.generateFutureExecutions(intervalPast);
        verify(futureExecutionRepository, never()).save(any());
    }

    @Test
    void mixedSlots_onlyFutureOnesCreated() {
        // 01:00 is past, 23:59 is future
        AgentScheduleDocument schedule = makeSchedule("sched-mix", "mix-agent",
                ScheduleType.FIXED_TIMES, null, List.of("01:00", "23:59"), null);

        service.generateFutureExecutions(schedule);

        verify(futureExecutionRepository, times(1)).save(execCaptor.capture());
        FutureExecutionDocument saved = execCaptor.getValue();
        assertThat(saved.getPlannedHour()).isEqualTo(23);
        assertThat(saved.getPlannedMinute()).isEqualTo(59);
        assertThat(saved.getScheduledAt()).isAfter(Instant.now());
    }

    /**
     * Regression test for the restart bug: cron agents scheduled for 09:00 UTC
     * would fire immediately on app restart because past slots were not skipped.
     * This test simulates all 4 weekday agents that were affected.
     */
    @Test
    void restartScenario_allPastAgents_noneCreated() {
        for (String agentId : List.of("reconcile-agent", "resource-agent",
                "objective-agent", "checklist-agent")) {
            AgentScheduleDocument schedule = makeSchedule("default-" + agentId, agentId,
                    ScheduleType.FIXED_TIMES, null, List.of("09:00"), null);
            service.generateFutureExecutions(schedule);
        }

        // 09:00 UTC is always in the past — nothing should be saved for any agent
        verify(futureExecutionRepository, never()).save(any());
    }

    // ------------------------------------------------------------------
    // Future slots created normally with correct fields
    // ------------------------------------------------------------------

    @Test
    void futureSlot_createdWithCorrectFields() {
        AgentScheduleDocument schedule = makeSchedule("sched-fields", "field-agent",
                ScheduleType.FIXED_TIMES, null, List.of("23:57"), null);
        schedule.setProjectId("proj-123");

        AgentScheduleDocument.ExecutorPolicy policy = new AgentScheduleDocument.ExecutorPolicy();
        policy.setPriority(8);
        policy.setMaxAttempts(5);
        policy.setRetryBackoffMs(30000);
        schedule.setExecutorPolicy(policy);

        service.generateFutureExecutions(schedule);

        verify(futureExecutionRepository).save(execCaptor.capture());
        FutureExecutionDocument saved = execCaptor.getValue();
        assertThat(saved.getAgentId()).isEqualTo("field-agent");
        assertThat(saved.getProjectId()).isEqualTo("proj-123");
        assertThat(saved.getExecStatus()).isEqualTo(ExecStatus.READY);
        assertThat(saved.getScheduledAt()).isAfter(Instant.now());
        assertThat(saved.getPriority()).isEqualTo(8);
        assertThat(saved.getMaxAttempts()).isEqualTo(5);
        assertThat(saved.getRetryBackoffMs()).isEqualTo(30000);
        assertThat(saved.getScheduleId()).isEqualTo("sched-fields");
        assertThat(saved.isImmediate()).isFalse();
        assertThat(saved.getPlannedHour()).isEqualTo(23);
        assertThat(saved.getIdempotencyKey()).contains("field-agent");
    }

    // ------------------------------------------------------------------
    // Idempotency, schedule types, immediate execution
    // ------------------------------------------------------------------

    @Test
    void duplicateIdempotencyKey_isSkipped() {
        when(futureExecutionRepository.existsByIdempotencyKey(anyString())).thenReturn(true);
        AgentScheduleDocument schedule = makeSchedule("sched-dup", "dup-agent",
                ScheduleType.FIXED_TIMES, null, List.of("23:56"), null);
        service.generateFutureExecutions(schedule);
        verify(futureExecutionRepository, never()).save(any());
    }

    @Test
    void nullAndImmediateScheduleTypes_produceNoExecutions() {
        service.generateFutureExecutions(makeSchedule("s1", "a1", null, null, null, null));
        service.generateFutureExecutions(makeSchedule("s2", "a2", ScheduleType.IMMEDIATE, null, null, null));
        verify(futureExecutionRepository, never()).save(any());
    }

    @Test
    void createImmediateExecution_setsCorrectFields() {
        service.createImmediateExecution("my-agent", "proj-1");

        verify(futureExecutionRepository).save(execCaptor.capture());
        FutureExecutionDocument saved = execCaptor.getValue();
        assertThat(saved.getAgentId()).isEqualTo("my-agent");
        assertThat(saved.getProjectId()).isEqualTo("proj-1");
        assertThat(saved.isImmediate()).isTrue();
        assertThat(saved.getExecStatus()).isEqualTo(ExecStatus.READY);
        assertThat(saved.getPriority()).isEqualTo(8);
    }

    // ------------------------------------------------------------------
    // Reconcile and rebuild
    // ------------------------------------------------------------------

    @Test
    void reconcile_disabledSchedule_cancelsStaleItems() {
        AgentScheduleDocument schedule = makeSchedule("sched-disabled", "disabled-agent",
                ScheduleType.FIXED_TIMES, null, List.of("23:55"), null);
        schedule.setEnabled(false);
        schedule.setUpdatedAt(Instant.now());

        FutureExecutionDocument staleItem = new FutureExecutionDocument();
        staleItem.setExecutionId("exec-stale");
        staleItem.setExecStatus(ExecStatus.READY);

        when(scheduleRepository.findByUpdatedAtGreaterThan(any())).thenReturn(List.of(schedule));
        when(futureExecutionRepository.findByScheduleIdAndExecStatusNotIn(eq("sched-disabled"), any()))
                .thenReturn(List.of(staleItem));

        service.reconcileSchedules();

        verify(futureExecutionRepository).save(execCaptor.capture());
        assertThat(execCaptor.getValue().getExecStatus()).isEqualTo(ExecStatus.CANCELLED);
    }

    @Test
    void rebuildDayPlan_deletesYesterdayAndRegenerates() {
        AgentScheduleDocument schedule = makeSchedule("sched-rebuild", "rebuild-agent",
                ScheduleType.FIXED_TIMES, null, List.of("23:53"), null);
        when(scheduleRepository.findByEnabled(true)).thenReturn(List.of(schedule));

        service.rebuildDayPlan("UTC");

        String yesterdayKey = LocalDate.now(ZoneId.of("UTC")).minusDays(1)
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        verify(futureExecutionRepository).deleteByDateKeyAndExecStatusIn(eq(yesterdayKey), any());
        verify(futureExecutionRepository).save(any());
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static AgentScheduleDocument makeSchedule(String scheduleId, String agentId,
                                                       ScheduleType type, String cronExpr,
                                                       List<String> timesOfDay,
                                                       Integer intervalMinutes) {
        AgentScheduleDocument doc = new AgentScheduleDocument();
        doc.setScheduleId(scheduleId);
        doc.setAgentId(agentId);
        doc.setEnabled(true);
        doc.setTimezone("UTC");
        doc.setScheduleType(type);
        doc.setCronExpr(cronExpr);
        doc.setTimesOfDay(timesOfDay);
        doc.setIntervalMinutes(intervalMinutes);
        doc.setCreatedAt(Instant.now());
        doc.setUpdatedAt(Instant.now());
        return doc;
    }
}
