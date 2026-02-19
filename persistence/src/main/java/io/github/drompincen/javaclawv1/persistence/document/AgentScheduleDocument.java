package io.github.drompincen.javaclawv1.persistence.document;

import io.github.drompincen.javaclawv1.protocol.api.ProjectScope;
import io.github.drompincen.javaclawv1.protocol.api.ScheduleType;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.List;

@Document(collection = "agent_schedules")
@CompoundIndex(name = "agent_project_idx", def = "{'agentId': 1, 'projectId': 1}", unique = true)
public class AgentScheduleDocument {

    @Id
    private String scheduleId;
    private String agentId;
    private boolean enabled = true;
    private String timezone = "UTC";
    private ScheduleType scheduleType;
    private String cronExpr;
    private List<String> timesOfDay;
    private Integer intervalMinutes;
    private ProjectScope projectScope = ProjectScope.GLOBAL;
    private String projectId;
    private InputsPolicy inputsPolicy;
    private ExecutorPolicy executorPolicy;
    @Version
    private long version;
    private Instant createdAt;
    private Instant updatedAt;

    public AgentScheduleDocument() {}

    // Embedded: InputsPolicy
    public static class InputsPolicy {
        private String inputType = "none";
        private Integer windowMinutes;
        private String threadFilter;

        public InputsPolicy() {}

        public String getInputType() { return inputType; }
        public void setInputType(String inputType) { this.inputType = inputType; }
        public Integer getWindowMinutes() { return windowMinutes; }
        public void setWindowMinutes(Integer windowMinutes) { this.windowMinutes = windowMinutes; }
        public String getThreadFilter() { return threadFilter; }
        public void setThreadFilter(String threadFilter) { this.threadFilter = threadFilter; }
    }

    // Embedded: ExecutorPolicy
    public static class ExecutorPolicy {
        private int maxConcurrent = 1;
        private int priority = 5;
        private int maxAttempts = 3;
        private long retryBackoffMs = 60000;
        private boolean riskGate = false;

        public ExecutorPolicy() {}

        public int getMaxConcurrent() { return maxConcurrent; }
        public void setMaxConcurrent(int maxConcurrent) { this.maxConcurrent = maxConcurrent; }
        public int getPriority() { return priority; }
        public void setPriority(int priority) { this.priority = priority; }
        public int getMaxAttempts() { return maxAttempts; }
        public void setMaxAttempts(int maxAttempts) { this.maxAttempts = maxAttempts; }
        public long getRetryBackoffMs() { return retryBackoffMs; }
        public void setRetryBackoffMs(long retryBackoffMs) { this.retryBackoffMs = retryBackoffMs; }
        public boolean isRiskGate() { return riskGate; }
        public void setRiskGate(boolean riskGate) { this.riskGate = riskGate; }
    }

    public String getScheduleId() { return scheduleId; }
    public void setScheduleId(String scheduleId) { this.scheduleId = scheduleId; }
    public String getAgentId() { return agentId; }
    public void setAgentId(String agentId) { this.agentId = agentId; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getTimezone() { return timezone; }
    public void setTimezone(String timezone) { this.timezone = timezone; }
    public ScheduleType getScheduleType() { return scheduleType; }
    public void setScheduleType(ScheduleType scheduleType) { this.scheduleType = scheduleType; }
    public String getCronExpr() { return cronExpr; }
    public void setCronExpr(String cronExpr) { this.cronExpr = cronExpr; }
    public List<String> getTimesOfDay() { return timesOfDay; }
    public void setTimesOfDay(List<String> timesOfDay) { this.timesOfDay = timesOfDay; }
    public Integer getIntervalMinutes() { return intervalMinutes; }
    public void setIntervalMinutes(Integer intervalMinutes) { this.intervalMinutes = intervalMinutes; }
    public ProjectScope getProjectScope() { return projectScope; }
    public void setProjectScope(ProjectScope projectScope) { this.projectScope = projectScope; }
    public String getProjectId() { return projectId; }
    public void setProjectId(String projectId) { this.projectId = projectId; }
    public InputsPolicy getInputsPolicy() { return inputsPolicy; }
    public void setInputsPolicy(InputsPolicy inputsPolicy) { this.inputsPolicy = inputsPolicy; }
    public ExecutorPolicy getExecutorPolicy() { return executorPolicy; }
    public void setExecutorPolicy(ExecutorPolicy executorPolicy) { this.executorPolicy = executorPolicy; }
    public long getVersion() { return version; }
    public void setVersion(long version) { this.version = version; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
