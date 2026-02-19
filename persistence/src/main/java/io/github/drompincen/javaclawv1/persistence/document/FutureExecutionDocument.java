package io.github.drompincen.javaclawv1.persistence.document;

import io.github.drompincen.javaclawv1.protocol.api.ExecStatus;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@Document(collection = "future_executions")
@CompoundIndex(name = "exec_pickup_idx", def = "{'execStatus': 1, 'scheduledAt': 1}")
@CompoundIndex(name = "date_agent_idx", def = "{'dateKey': 1, 'agentId': 1, 'projectId': 1}")
public class FutureExecutionDocument {

    @Id
    private String executionId;
    @Indexed(unique = true)
    private String idempotencyKey;
    private String dateKey;
    private String agentId;
    private String projectId;
    private String timezone;
    private Instant scheduledAt;
    private int plannedHour;
    private int plannedMinute;
    private boolean immediate;
    private ExecStatus execStatus = ExecStatus.READY;
    private int priority = 5;
    private String lockOwner;
    private Instant lockedAt;
    private Instant leaseUntil;
    private int attempt;
    private int maxAttempts = 3;
    private long retryBackoffMs = 60000;
    private String scheduleId;
    private long createdFromScheduleVersion;
    private Instant createdAt;
    private Instant lastUpdatedAt;

    public FutureExecutionDocument() {}

    public String getExecutionId() { return executionId; }
    public void setExecutionId(String executionId) { this.executionId = executionId; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }
    public String getDateKey() { return dateKey; }
    public void setDateKey(String dateKey) { this.dateKey = dateKey; }
    public String getAgentId() { return agentId; }
    public void setAgentId(String agentId) { this.agentId = agentId; }
    public String getProjectId() { return projectId; }
    public void setProjectId(String projectId) { this.projectId = projectId; }
    public String getTimezone() { return timezone; }
    public void setTimezone(String timezone) { this.timezone = timezone; }
    public Instant getScheduledAt() { return scheduledAt; }
    public void setScheduledAt(Instant scheduledAt) { this.scheduledAt = scheduledAt; }
    public int getPlannedHour() { return plannedHour; }
    public void setPlannedHour(int plannedHour) { this.plannedHour = plannedHour; }
    public int getPlannedMinute() { return plannedMinute; }
    public void setPlannedMinute(int plannedMinute) { this.plannedMinute = plannedMinute; }
    public boolean isImmediate() { return immediate; }
    public void setImmediate(boolean immediate) { this.immediate = immediate; }
    public ExecStatus getExecStatus() { return execStatus; }
    public void setExecStatus(ExecStatus execStatus) { this.execStatus = execStatus; }
    public int getPriority() { return priority; }
    public void setPriority(int priority) { this.priority = priority; }
    public String getLockOwner() { return lockOwner; }
    public void setLockOwner(String lockOwner) { this.lockOwner = lockOwner; }
    public Instant getLockedAt() { return lockedAt; }
    public void setLockedAt(Instant lockedAt) { this.lockedAt = lockedAt; }
    public Instant getLeaseUntil() { return leaseUntil; }
    public void setLeaseUntil(Instant leaseUntil) { this.leaseUntil = leaseUntil; }
    public int getAttempt() { return attempt; }
    public void setAttempt(int attempt) { this.attempt = attempt; }
    public int getMaxAttempts() { return maxAttempts; }
    public void setMaxAttempts(int maxAttempts) { this.maxAttempts = maxAttempts; }
    public long getRetryBackoffMs() { return retryBackoffMs; }
    public void setRetryBackoffMs(long retryBackoffMs) { this.retryBackoffMs = retryBackoffMs; }
    public String getScheduleId() { return scheduleId; }
    public void setScheduleId(String scheduleId) { this.scheduleId = scheduleId; }
    public long getCreatedFromScheduleVersion() { return createdFromScheduleVersion; }
    public void setCreatedFromScheduleVersion(long createdFromScheduleVersion) { this.createdFromScheduleVersion = createdFromScheduleVersion; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getLastUpdatedAt() { return lastUpdatedAt; }
    public void setLastUpdatedAt(Instant lastUpdatedAt) { this.lastUpdatedAt = lastUpdatedAt; }
}
