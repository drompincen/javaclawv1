package io.github.drompincen.javaclawv1.runtime.scheduler;

import io.github.drompincen.javaclawv1.persistence.document.FutureExecutionDocument;
import io.github.drompincen.javaclawv1.persistence.document.MessageDocument;
import io.github.drompincen.javaclawv1.persistence.document.PastExecutionDocument;
import io.github.drompincen.javaclawv1.persistence.document.SessionDocument;
import io.github.drompincen.javaclawv1.persistence.repository.FutureExecutionRepository;
import io.github.drompincen.javaclawv1.persistence.repository.MessageRepository;
import io.github.drompincen.javaclawv1.persistence.repository.PastExecutionRepository;
import io.github.drompincen.javaclawv1.persistence.repository.SessionRepository;
import io.github.drompincen.javaclawv1.protocol.api.ExecStatus;
import io.github.drompincen.javaclawv1.protocol.api.ResultStatus;
import io.github.drompincen.javaclawv1.protocol.api.SessionStatus;
import io.github.drompincen.javaclawv1.runtime.agent.AgentLoop;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;

@Service
public class ExecutionEngineService {

    private static final Logger log = LoggerFactory.getLogger(ExecutionEngineService.class);
    private static final long LEASE_DURATION_MS = 90_000;
    private static final long STALE_LEASE_GRACE_MS = 30_000;

    private final FutureExecutionRepository futureExecutionRepository;
    private final PastExecutionRepository pastExecutionRepository;
    private final SessionRepository sessionRepository;
    private final MessageRepository messageRepository;
    private final AgentLoop agentLoop;
    private final LeaseHeartbeatService leaseHeartbeatService;
    private final String instanceId = UUID.randomUUID().toString().substring(0, 8);

    public ExecutionEngineService(FutureExecutionRepository futureExecutionRepository,
                                  PastExecutionRepository pastExecutionRepository,
                                  SessionRepository sessionRepository,
                                  MessageRepository messageRepository,
                                  AgentLoop agentLoop,
                                  LeaseHeartbeatService leaseHeartbeatService) {
        this.futureExecutionRepository = futureExecutionRepository;
        this.pastExecutionRepository = pastExecutionRepository;
        this.sessionRepository = sessionRepository;
        this.messageRepository = messageRepository;
        this.agentLoop = agentLoop;
        this.leaseHeartbeatService = leaseHeartbeatService;
    }

    @Scheduled(fixedDelayString = "${javaclaw.scheduler.executor-poll-interval-ms:5000}")
    public void pollAndExecute() {
        // Recover stale leases first
        recoverStaleLeases();

        // Find READY items that are due
        List<FutureExecutionDocument> readyItems =
                futureExecutionRepository.findByExecStatusAndScheduledAtLessThanEqualOrderByPriorityDescScheduledAtAsc(
                        ExecStatus.READY, Instant.now());

        for (FutureExecutionDocument item : readyItems) {
            // Check per-agent concurrency
            long runningCount = futureExecutionRepository.findByAgentIdAndExecStatus(
                    item.getAgentId(), ExecStatus.RUNNING).size();
            int maxConcurrent = 1; // Default, could read from schedule
            if (runningCount >= maxConcurrent) continue;

            // Try to claim
            if (claimExecution(item)) {
                executeAsync(item);
            }
        }
    }

    private boolean claimExecution(FutureExecutionDocument item) {
        // Optimistic claim: read, check status, update
        Optional<FutureExecutionDocument> fresh = futureExecutionRepository.findById(item.getExecutionId());
        if (fresh.isEmpty() || fresh.get().getExecStatus() != ExecStatus.READY) return false;

        FutureExecutionDocument exec = fresh.get();
        exec.setExecStatus(ExecStatus.PENDING);
        exec.setLockOwner(instanceId);
        exec.setLockedAt(Instant.now());
        exec.setLeaseUntil(Instant.now().plusMillis(LEASE_DURATION_MS));
        exec.setLastUpdatedAt(Instant.now());

        try {
            futureExecutionRepository.save(exec);
            log.info("Claimed execution {} for agent {}", exec.getExecutionId(), exec.getAgentId());
            return true;
        } catch (Exception e) {
            log.debug("Failed to claim execution {}: {}", exec.getExecutionId(), e.getMessage());
            return false;
        }
    }

    private void executeAsync(FutureExecutionDocument exec) {
        Thread.ofVirtual().name("agent-exec-" + exec.getAgentId()).start(() -> {
            Instant startedAt = Instant.now();
            String sessionId = null;

            try {
                // Mark RUNNING
                exec.setExecStatus(ExecStatus.RUNNING);
                exec.setLastUpdatedAt(Instant.now());
                futureExecutionRepository.save(exec);

                // Start heartbeat
                leaseHeartbeatService.startHeartbeat(exec.getExecutionId());

                // Create session for this execution
                SessionDocument session = new SessionDocument();
                sessionId = UUID.randomUUID().toString();
                session.setSessionId(sessionId);
                session.setProjectId(exec.getProjectId());
                session.setStatus(SessionStatus.IDLE);
                session.setCreatedAt(Instant.now());

                Map<String, String> metadata = new HashMap<>();
                metadata.put("type", "scheduled_execution");
                metadata.put("executionId", exec.getExecutionId());
                metadata.put("agentId", exec.getAgentId());
                session.setMetadata(metadata);

                sessionRepository.save(session);

                // Seed user message so the LLM has at least one message
                seedScheduledPrompt(sessionId, exec.getAgentId(), exec.getProjectId());

                // Run agent loop
                agentLoop.startAsync(sessionId);

                // Wait for completion by polling session status
                SessionStatus finalStatus = waitForCompletion(sessionId, 300_000); // 5 min timeout

                // Stop heartbeat
                leaseHeartbeatService.stopHeartbeat(exec.getExecutionId());

                // Record past execution
                Instant endedAt = Instant.now();
                PastExecutionDocument past = new PastExecutionDocument();
                past.setPastExecutionId(UUID.randomUUID().toString());
                past.setExecutionId(exec.getExecutionId());
                past.setAgentId(exec.getAgentId());
                past.setProjectId(exec.getProjectId());
                past.setScheduleId(exec.getScheduleId());
                past.setScheduledAt(exec.getScheduledAt());
                past.setStartedAt(startedAt);
                past.setEndedAt(endedAt);
                past.setDurationMs(endedAt.toEpochMilli() - startedAt.toEpochMilli());
                past.setSessionId(sessionId);
                past.setAttempt(exec.getAttempt() + 1);
                past.setCreatedAt(Instant.now());

                if (finalStatus == SessionStatus.COMPLETED) {
                    past.setResultStatus(ResultStatus.SUCCESS);
                    // Delete the future execution
                    futureExecutionRepository.deleteById(exec.getExecutionId());
                } else {
                    past.setResultStatus(ResultStatus.FAIL);
                    past.setErrorMessage("Session ended with status: " + finalStatus);
                    handleFailure(exec);
                }

                pastExecutionRepository.save(past);
                log.info("Execution {} completed: {} ({}ms)", exec.getExecutionId(),
                        past.getResultStatus(), past.getDurationMs());

            } catch (Exception e) {
                log.error("Execution {} failed: {}", exec.getExecutionId(), e.getMessage(), e);
                leaseHeartbeatService.stopHeartbeat(exec.getExecutionId());

                PastExecutionDocument past = new PastExecutionDocument();
                past.setPastExecutionId(UUID.randomUUID().toString());
                past.setExecutionId(exec.getExecutionId());
                past.setAgentId(exec.getAgentId());
                past.setProjectId(exec.getProjectId());
                past.setScheduleId(exec.getScheduleId());
                past.setScheduledAt(exec.getScheduledAt());
                past.setStartedAt(startedAt);
                past.setEndedAt(Instant.now());
                past.setDurationMs(Instant.now().toEpochMilli() - startedAt.toEpochMilli());
                past.setResultStatus(ResultStatus.FAIL);
                past.setErrorCode("EXECUTION_ERROR");
                past.setErrorMessage(e.getMessage());
                past.setSessionId(sessionId);
                past.setAttempt(exec.getAttempt() + 1);
                past.setCreatedAt(Instant.now());
                pastExecutionRepository.save(past);

                handleFailure(exec);
            }
        });
    }

    private SessionStatus waitForCompletion(String sessionId, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            Optional<SessionDocument> session = sessionRepository.findById(sessionId);
            if (session.isPresent()) {
                SessionStatus status = session.get().getStatus();
                if (status == SessionStatus.COMPLETED || status == SessionStatus.FAILED) {
                    return status;
                }
            }
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return SessionStatus.FAILED;
            }
        }
        return SessionStatus.FAILED; // Timeout
    }

    private void handleFailure(FutureExecutionDocument exec) {
        if (exec.getAttempt() + 1 < exec.getMaxAttempts()) {
            exec.setExecStatus(ExecStatus.FAILED_RETRYABLE);
            exec.setAttempt(exec.getAttempt() + 1);
            exec.setScheduledAt(Instant.now().plusMillis(exec.getRetryBackoffMs()));
            exec.setLockOwner(null);
            exec.setLeaseUntil(null);
            exec.setLastUpdatedAt(Instant.now());
            futureExecutionRepository.save(exec);
            log.info("Execution {} queued for retry (attempt {})", exec.getExecutionId(), exec.getAttempt());
        } else {
            exec.setExecStatus(ExecStatus.CANCELLED);
            exec.setLastUpdatedAt(Instant.now());
            futureExecutionRepository.save(exec);
            log.warn("Execution {} exhausted retries ({} attempts)", exec.getExecutionId(), exec.getMaxAttempts());
        }
    }

    private void seedScheduledPrompt(String sessionId, String agentId, String projectId) {
        String projectClause = projectId != null ? " for project " + projectId : "";
        String prompt = switch (agentId) {
            case "objective-agent" -> "Run scheduled objective analysis" + projectClause
                    + ". Call compute_coverage to analyze tickets and objectives, then summarize findings.";
            case "reconcile-agent" -> "Run scheduled reconciliation" + projectClause
                    + ". Read tickets, objectives, and phases, then cross-reference and create a delta pack for any discrepancies found.";
            case "resource-agent" -> "Run scheduled resource analysis" + projectClause
                    + ". Read resources and tickets, compute capacity report, and flag any overloaded team members.";
            case "checklist-agent" -> "Run scheduled checklist review" + projectClause
                    + ". Read checklists and report on progress for any open items.";
            default -> "Run your scheduled task" + projectClause + ". Use available tools to analyze project data and report findings.";
        };

        MessageDocument msg = new MessageDocument();
        msg.setMessageId(UUID.randomUUID().toString());
        msg.setSessionId(sessionId);
        msg.setSeq(1);
        msg.setRole("user");
        msg.setContent(prompt);
        msg.setTimestamp(Instant.now());
        messageRepository.save(msg);
    }

    private void recoverStaleLeases() {
        Instant threshold = Instant.now().minusMillis(STALE_LEASE_GRACE_MS);
        List<FutureExecutionDocument> stale = futureExecutionRepository.findByLeaseUntilLessThanAndExecStatusIn(
                threshold, List.of(ExecStatus.RUNNING, ExecStatus.PENDING));

        for (FutureExecutionDocument item : stale) {
            if (item.getAttempt() > 0) {
                item.setExecStatus(ExecStatus.FAILED_RETRYABLE);
            } else {
                item.setExecStatus(ExecStatus.READY);
            }
            item.setLockOwner(null);
            item.setLeaseUntil(null);
            item.setLastUpdatedAt(Instant.now());
            futureExecutionRepository.save(item);
            log.info("Recovered stale execution {} (was {})", item.getExecutionId(), item.getExecStatus());
        }
    }
}
