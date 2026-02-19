package io.github.drompincen.javaclawv1.gateway.controller;

import io.github.drompincen.javaclawv1.persistence.document.AgentScheduleDocument;
import io.github.drompincen.javaclawv1.persistence.document.FutureExecutionDocument;
import io.github.drompincen.javaclawv1.persistence.document.PastExecutionDocument;
import io.github.drompincen.javaclawv1.persistence.repository.AgentScheduleRepository;
import io.github.drompincen.javaclawv1.persistence.repository.FutureExecutionRepository;
import io.github.drompincen.javaclawv1.persistence.repository.PastExecutionRepository;
import io.github.drompincen.javaclawv1.protocol.api.*;
import io.github.drompincen.javaclawv1.runtime.scheduler.SchedulePlannerService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import org.springframework.scheduling.support.CronExpression;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
public class ScheduleController {

    private final AgentScheduleRepository scheduleRepository;
    private final FutureExecutionRepository futureExecutionRepository;
    private final PastExecutionRepository pastExecutionRepository;
    private final SchedulePlannerService plannerService;

    public ScheduleController(AgentScheduleRepository scheduleRepository,
                              FutureExecutionRepository futureExecutionRepository,
                              PastExecutionRepository pastExecutionRepository,
                              SchedulePlannerService plannerService) {
        this.scheduleRepository = scheduleRepository;
        this.futureExecutionRepository = futureExecutionRepository;
        this.pastExecutionRepository = pastExecutionRepository;
        this.plannerService = plannerService;
    }

    // --- Agent Schedule CRUD ---

    @PostMapping("/api/schedules")
    public ResponseEntity<ScheduleResponse> createSchedule(@RequestBody ScheduleRequest req) {
        AgentScheduleDocument doc = new AgentScheduleDocument();
        doc.setScheduleId(UUID.randomUUID().toString());
        doc.setAgentId(req.agentId());
        doc.setEnabled(req.enabled() != null ? req.enabled() : true);
        doc.setTimezone(req.timezone() != null ? req.timezone() : "UTC");
        doc.setScheduleType(req.scheduleType());
        doc.setCronExpr(req.cronExpr());
        doc.setTimesOfDay(req.timesOfDay());
        doc.setIntervalMinutes(req.intervalMinutes());
        doc.setProjectScope(req.projectScope() != null ? req.projectScope() : ProjectScope.GLOBAL);
        doc.setProjectId(req.projectId());
        doc.setCreatedAt(Instant.now());
        doc.setUpdatedAt(Instant.now());

        scheduleRepository.save(doc);

        // If IMMEDIATE, create a ready future execution
        if (req.scheduleType() == ScheduleType.IMMEDIATE) {
            plannerService.createImmediateExecution(req.agentId(), req.projectId());
        }

        return ResponseEntity.status(HttpStatus.CREATED).body(toScheduleResponse(doc));
    }

    @GetMapping("/api/schedules")
    public List<ScheduleResponse> listSchedules(
            @RequestParam(required = false) String agentId,
            @RequestParam(required = false) Boolean enabled) {
        List<AgentScheduleDocument> schedules;
        if (agentId != null) {
            schedules = scheduleRepository.findByAgentId(agentId);
        } else if (enabled != null) {
            schedules = scheduleRepository.findByEnabled(enabled);
        } else {
            schedules = scheduleRepository.findAll();
        }
        return schedules.stream().map(this::toScheduleResponse).collect(Collectors.toList());
    }

    @GetMapping("/api/schedules/{scheduleId}")
    public ResponseEntity<ScheduleResponse> getSchedule(@PathVariable String scheduleId) {
        return scheduleRepository.findById(scheduleId)
                .map(doc -> ResponseEntity.ok(toScheduleResponse(doc)))
                .orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("/api/schedules/{scheduleId}")
    public ResponseEntity<ScheduleResponse> updateSchedule(@PathVariable String scheduleId,
                                                           @RequestBody ScheduleRequest req) {
        return scheduleRepository.findById(scheduleId).map(existing -> {
            if (req.enabled() != null) existing.setEnabled(req.enabled());
            if (req.timezone() != null) existing.setTimezone(req.timezone());
            if (req.scheduleType() != null) existing.setScheduleType(req.scheduleType());
            if (req.cronExpr() != null) existing.setCronExpr(req.cronExpr());
            if (req.timesOfDay() != null) existing.setTimesOfDay(req.timesOfDay());
            if (req.intervalMinutes() != null) existing.setIntervalMinutes(req.intervalMinutes());
            if (req.projectScope() != null) existing.setProjectScope(req.projectScope());
            if (req.projectId() != null) existing.setProjectId(req.projectId());
            existing.setUpdatedAt(Instant.now());
            scheduleRepository.save(existing);
            return ResponseEntity.ok(toScheduleResponse(existing));
        }).orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/api/schedules/{scheduleId}")
    public ResponseEntity<Void> deleteSchedule(@PathVariable String scheduleId) {
        if (scheduleRepository.existsById(scheduleId)) {
            // Cancel all pending future executions
            List<FutureExecutionDocument> items = futureExecutionRepository.findByScheduleIdAndExecStatusNotIn(
                    scheduleId, List.of(ExecStatus.RUNNING));
            items.forEach(item -> {
                item.setExecStatus(ExecStatus.CANCELLED);
                item.setLastUpdatedAt(Instant.now());
                futureExecutionRepository.save(item);
            });
            scheduleRepository.deleteById(scheduleId);
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.notFound().build();
    }

    // --- Future Executions ---

    @GetMapping("/api/executions/future")
    public List<ExecutionStatusResponse> listFutureExecutions(
            @RequestParam(required = false) String agentId,
            @RequestParam(required = false) String dateKey,
            @RequestParam(required = false) ExecStatus execStatus) {
        String dk = dateKey != null ? dateKey : LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        List<FutureExecutionDocument> items;
        if (agentId != null) {
            items = futureExecutionRepository.findByAgentId(agentId);
        } else if (execStatus != null) {
            items = futureExecutionRepository.findByExecStatus(execStatus);
        } else {
            items = futureExecutionRepository.findByDateKey(dk);
        }
        return items.stream().map(this::toExecResponse).collect(Collectors.toList());
    }

    @PostMapping("/api/executions/future/{executionId}/cancel")
    public ResponseEntity<ExecutionStatusResponse> cancelExecution(@PathVariable String executionId) {
        return futureExecutionRepository.findById(executionId).map(exec -> {
            if (exec.getExecStatus() == ExecStatus.RUNNING) {
                return ResponseEntity.status(HttpStatus.CONFLICT).body(toExecResponse(exec));
            }
            exec.setExecStatus(ExecStatus.CANCELLED);
            exec.setLastUpdatedAt(Instant.now());
            futureExecutionRepository.save(exec);
            return ResponseEntity.ok(toExecResponse(exec));
        }).orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/api/executions/trigger")
    public ResponseEntity<ExecutionStatusResponse> triggerExecution(@RequestBody Map<String, String> body) {
        String agentId = body.get("agentId");
        String projectId = body.get("projectId");
        if (agentId == null || agentId.isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        FutureExecutionDocument exec = plannerService.createImmediateExecution(agentId, projectId);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(toExecResponse(exec));
    }

    // --- Past Executions ---

    @GetMapping("/api/executions/past")
    public Page<PastExecutionResponse> listPastExecutions(
            @RequestParam(required = false) String agentId,
            @RequestParam(required = false) String projectId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        PageRequest pageable = PageRequest.of(page, size);
        Page<PastExecutionDocument> results;
        if (agentId != null) {
            results = pastExecutionRepository.findByAgentIdOrderByStartedAtDesc(agentId, pageable);
        } else if (projectId != null) {
            results = pastExecutionRepository.findByProjectIdOrderByStartedAtDesc(projectId, pageable);
        } else {
            results = pastExecutionRepository.findAllByOrderByStartedAtDesc(pageable);
        }
        return results.map(this::toPastResponse);
    }

    @GetMapping("/api/executions/past/{pastExecutionId}")
    public ResponseEntity<PastExecutionResponse> getPastExecution(@PathVariable String pastExecutionId) {
        return pastExecutionRepository.findById(pastExecutionId)
                .map(doc -> ResponseEntity.ok(toPastResponse(doc)))
                .orElse(ResponseEntity.notFound().build());
    }

    // --- DTO Mappers ---

    private ScheduleResponse toScheduleResponse(AgentScheduleDocument doc) {
        Instant nextExec = null;
        if (doc.isEnabled() && doc.getScheduleType() == ScheduleType.CRON && doc.getCronExpr() != null) {
            try {
                ZoneId tz = ZoneId.of(doc.getTimezone() != null ? doc.getTimezone() : "UTC");
                CronExpression cron = CronExpression.parse(doc.getCronExpr());
                LocalDateTime next = cron.next(LocalDateTime.now(tz));
                if (next != null) {
                    nextExec = next.atZone(tz).toInstant();
                }
            } catch (Exception ignored) { /* invalid cron or timezone — return null */ }
        } else if (doc.isEnabled() && doc.getScheduleType() == ScheduleType.FIXED_TIMES && doc.getTimesOfDay() != null) {
            ZoneId tz = ZoneId.of(doc.getTimezone() != null ? doc.getTimezone() : "UTC");
            LocalDateTime now = LocalDateTime.now(tz);
            nextExec = doc.getTimesOfDay().stream()
                    .map(t -> LocalTime.parse(t))
                    .map(lt -> {
                        LocalDateTime candidate = now.toLocalDate().atTime(lt);
                        return candidate.isAfter(now) ? candidate : candidate.plusDays(1);
                    })
                    .min(LocalDateTime::compareTo)
                    .map(ldt -> ldt.atZone(tz).toInstant())
                    .orElse(null);
        }
        return new ScheduleResponse(
                doc.getScheduleId(),
                doc.getAgentId(),
                doc.isEnabled(),
                doc.getScheduleType(),
                doc.getCronExpr(),
                doc.getProjectScope(),
                doc.getProjectId(),
                nextExec,
                doc.getVersion(),
                doc.getCreatedAt(),
                doc.getUpdatedAt()
        );
    }

    private ExecutionStatusResponse toExecResponse(FutureExecutionDocument doc) {
        return new ExecutionStatusResponse(
                doc.getExecutionId(),
                doc.getAgentId(),
                doc.getProjectId(),
                doc.getScheduledAt(),
                doc.getPlannedHour(),
                doc.getPlannedMinute(),
                doc.isImmediate(),
                doc.getExecStatus(),
                doc.getAttempt(),
                doc.getLockOwner()
        );
    }

    private PastExecutionResponse toPastResponse(PastExecutionDocument doc) {
        return new PastExecutionResponse(
                doc.getPastExecutionId(),
                doc.getAgentId(),
                doc.getProjectId(),
                doc.getScheduledAt(),
                doc.getStartedAt(),
                doc.getEndedAt(),
                doc.getDurationMs(),
                doc.getResultStatus(),
                doc.getErrorMessage(),
                doc.getResponseSummary(),
                doc.getAttempt()
        );
    }
}
