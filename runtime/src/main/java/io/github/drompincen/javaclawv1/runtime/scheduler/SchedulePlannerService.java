package io.github.drompincen.javaclawv1.runtime.scheduler;

import io.github.drompincen.javaclawv1.persistence.document.AgentScheduleDocument;
import io.github.drompincen.javaclawv1.persistence.document.FutureExecutionDocument;
import io.github.drompincen.javaclawv1.persistence.repository.AgentScheduleRepository;
import io.github.drompincen.javaclawv1.persistence.repository.FutureExecutionRepository;
import io.github.drompincen.javaclawv1.protocol.api.ExecStatus;
import io.github.drompincen.javaclawv1.protocol.api.ScheduleType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
public class SchedulePlannerService {

    private static final Logger log = LoggerFactory.getLogger(SchedulePlannerService.class);
    private static final DateTimeFormatter DATE_KEY_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final AgentScheduleRepository scheduleRepository;
    private final FutureExecutionRepository futureExecutionRepository;

    private Instant lastReconcileAt = Instant.EPOCH;

    public SchedulePlannerService(AgentScheduleRepository scheduleRepository,
                                  FutureExecutionRepository futureExecutionRepository) {
        this.scheduleRepository = scheduleRepository;
        this.futureExecutionRepository = futureExecutionRepository;
    }

    @Scheduled(fixedDelayString = "${javaclaw.scheduler.planner-interval-ms:60000}")
    public void reconcileSchedules() {
        List<AgentScheduleDocument> changed = scheduleRepository.findByUpdatedAtGreaterThan(lastReconcileAt);
        if (changed.isEmpty()) return;

        log.info("Reconciling {} changed schedules", changed.size());
        for (AgentScheduleDocument schedule : changed) {
            if (!schedule.isEnabled()) {
                cancelStaleItems(schedule);
                continue;
            }
            generateFutureExecutions(schedule);
        }
        lastReconcileAt = Instant.now();
    }

    public void generateFutureExecutions(AgentScheduleDocument schedule) {
        ZoneId tz = ZoneId.of(schedule.getTimezone() != null ? schedule.getTimezone() : "UTC");
        LocalDate today = LocalDate.now(tz);
        String dateKey = today.format(DATE_KEY_FMT);

        List<LocalTime> executionTimes = computeExecutionTimes(schedule);
        int created = 0;

        for (LocalTime time : executionTimes) {
            ZonedDateTime zdt = ZonedDateTime.of(today, time, tz);
            Instant scheduledAt = zdt.toInstant();

            // Don't backfill past time slots — prevents agents from firing on restart
            if (scheduledAt.isBefore(Instant.now())) continue;

            String idempotencyKey = dateKey + "|" + schedule.getAgentId() + "|"
                    + (schedule.getProjectId() != null ? schedule.getProjectId() : "GLOBAL")
                    + "|" + scheduledAt;

            if (futureExecutionRepository.existsByIdempotencyKey(idempotencyKey)) continue;

            FutureExecutionDocument exec = new FutureExecutionDocument();
            exec.setExecutionId(UUID.randomUUID().toString());
            exec.setIdempotencyKey(idempotencyKey);
            exec.setDateKey(dateKey);
            exec.setAgentId(schedule.getAgentId());
            exec.setProjectId(schedule.getProjectId());
            exec.setTimezone(schedule.getTimezone());
            exec.setScheduledAt(scheduledAt);
            exec.setPlannedHour(time.getHour());
            exec.setPlannedMinute(time.getMinute());
            exec.setImmediate(false);
            exec.setExecStatus(ExecStatus.READY);

            AgentScheduleDocument.ExecutorPolicy policy = schedule.getExecutorPolicy();
            if (policy != null) {
                exec.setPriority(policy.getPriority());
                exec.setMaxAttempts(policy.getMaxAttempts());
                exec.setRetryBackoffMs(policy.getRetryBackoffMs());
            }

            exec.setScheduleId(schedule.getScheduleId());
            exec.setCreatedFromScheduleVersion(schedule.getVersion());
            exec.setCreatedAt(Instant.now());
            exec.setLastUpdatedAt(Instant.now());

            futureExecutionRepository.save(exec);
            created++;
        }

        if (created > 0) {
            log.info("Created {} future executions for schedule {} (agent={})",
                    created, schedule.getScheduleId(), schedule.getAgentId());
        }
    }

    public FutureExecutionDocument createImmediateExecution(String agentId, String projectId) {
        String dateKey = LocalDate.now().format(DATE_KEY_FMT);
        Instant now = Instant.now();
        String idempotencyKey = dateKey + "|" + agentId + "|"
                + (projectId != null ? projectId : "GLOBAL")
                + "|IMMEDIATE|" + now;

        FutureExecutionDocument exec = new FutureExecutionDocument();
        exec.setExecutionId(UUID.randomUUID().toString());
        exec.setIdempotencyKey(idempotencyKey);
        exec.setDateKey(dateKey);
        exec.setAgentId(agentId);
        exec.setProjectId(projectId);
        exec.setTimezone("UTC");
        exec.setScheduledAt(now);
        exec.setPlannedHour(-1);
        exec.setPlannedMinute(0);
        exec.setImmediate(true);
        exec.setExecStatus(ExecStatus.READY);
        exec.setPriority(8); // Immediate gets higher priority
        exec.setMaxAttempts(3);
        exec.setCreatedAt(now);
        exec.setLastUpdatedAt(now);

        return futureExecutionRepository.save(exec);
    }

    private List<LocalTime> computeExecutionTimes(AgentScheduleDocument schedule) {
        if (schedule.getScheduleType() == null) return List.of();

        return switch (schedule.getScheduleType()) {
            case FIXED_TIMES -> {
                if (schedule.getTimesOfDay() == null) yield List.of();
                yield schedule.getTimesOfDay().stream()
                        .map(LocalTime::parse)
                        .sorted()
                        .toList();
            }
            case INTERVAL -> {
                if (schedule.getIntervalMinutes() == null || schedule.getIntervalMinutes() <= 0) yield List.of();
                List<LocalTime> times = new ArrayList<>();
                LocalTime t = LocalTime.MIDNIGHT;
                while (t.isBefore(LocalTime.of(23, 59))) {
                    times.add(t);
                    t = t.plusMinutes(schedule.getIntervalMinutes());
                    if (t.isBefore(times.get(times.size() - 1))) break; // Wrapped past midnight
                }
                yield times;
            }
            case CRON -> {
                if (schedule.getCronExpr() == null) yield List.of();
                try {
                    CronExpression cron = CronExpression.parse(schedule.getCronExpr());
                    ZoneId tz = ZoneId.of(schedule.getTimezone() != null ? schedule.getTimezone() : "UTC");
                    LocalDate today = LocalDate.now(tz);
                    ZonedDateTime start = today.atStartOfDay(tz);
                    ZonedDateTime end = today.plusDays(1).atStartOfDay(tz);

                    List<LocalTime> times = new ArrayList<>();
                    LocalDateTime next = cron.next(start.toLocalDateTime());
                    while (next != null && next.atZone(tz).isBefore(end)) {
                        times.add(next.toLocalTime());
                        next = cron.next(next);
                    }
                    yield times;
                } catch (Exception e) {
                    log.warn("Invalid cron expression '{}': {}", schedule.getCronExpr(), e.getMessage());
                    yield List.of();
                }
            }
            case IMMEDIATE -> List.of(); // Handled by createImmediateExecution
        };
    }

    private void cancelStaleItems(AgentScheduleDocument schedule) {
        List<FutureExecutionDocument> items = futureExecutionRepository.findByScheduleIdAndExecStatusNotIn(
                schedule.getScheduleId(),
                List.of(ExecStatus.RUNNING, ExecStatus.PENDING));
        for (FutureExecutionDocument item : items) {
            item.setExecStatus(ExecStatus.CANCELLED);
            item.setLastUpdatedAt(Instant.now());
            futureExecutionRepository.save(item);
        }
        if (!items.isEmpty()) {
            log.info("Cancelled {} stale items for disabled schedule {}", items.size(), schedule.getScheduleId());
        }
    }

    public void rebuildDayPlan(String timezone) {
        ZoneId tz = ZoneId.of(timezone);
        String yesterdayKey = LocalDate.now(tz).minusDays(1).format(DATE_KEY_FMT);
        String todayKey = LocalDate.now(tz).format(DATE_KEY_FMT);

        // Clean up yesterday's non-running items
        futureExecutionRepository.deleteByDateKeyAndExecStatusIn(yesterdayKey,
                List.of(ExecStatus.READY, ExecStatus.CANCELLED, ExecStatus.SKIPPED, ExecStatus.FAILED_RETRYABLE));

        // Regenerate today's plan
        List<AgentScheduleDocument> schedules = scheduleRepository.findByEnabled(true);
        for (AgentScheduleDocument schedule : schedules) {
            String schedTz = schedule.getTimezone() != null ? schedule.getTimezone() : "UTC";
            if (schedTz.equals(timezone)) {
                generateFutureExecutions(schedule);
            }
        }
        log.info("Midnight rebuild complete for timezone {}", timezone);
    }
}
