package io.github.drompincen.javaclawv1.persistence.document;

import io.github.drompincen.javaclawv1.protocol.api.ResultStatus;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.List;

@Document(collection = "past_executions")
@CompoundIndex(name = "agent_started_idx", def = "{'agentId': 1, 'startedAt': -1}")
@CompoundIndex(name = "project_started_idx", def = "{'projectId': 1, 'startedAt': -1}")
public class PastExecutionDocument {

    @Id
    private String pastExecutionId;
    @Indexed
    private String executionId;
    private String agentId;
    private String projectId;
    private String scheduleId;
    private Instant scheduledAt;
    private Instant startedAt;
    private Instant endedAt;
    private long durationMs;
    private ResultStatus resultStatus;
    private String errorCode;
    private String errorMessage;
    private String responseSummary;
    private ResponseRefs responseRefs;
    private LlmMetrics llmMetrics;
    private ToolCallSummary toolCallSummary;
    private String sessionId;
    private String threadId;
    private int attempt;
    private Instant createdAt;

    public PastExecutionDocument() {}

    // Embedded: ResponseRefs
    public static class ResponseRefs {
        private List<String> threadIds;
        private List<String> ticketIds;
        private List<String> checklistIds;
        private List<String> reminderIds;
        private List<String> blindspotIds;
        private List<String> memoryKeys;

        public ResponseRefs() {}

        public List<String> getThreadIds() { return threadIds; }
        public void setThreadIds(List<String> threadIds) { this.threadIds = threadIds; }
        public List<String> getTicketIds() { return ticketIds; }
        public void setTicketIds(List<String> ticketIds) { this.ticketIds = ticketIds; }
        public List<String> getChecklistIds() { return checklistIds; }
        public void setChecklistIds(List<String> checklistIds) { this.checklistIds = checklistIds; }
        public List<String> getReminderIds() { return reminderIds; }
        public void setReminderIds(List<String> reminderIds) { this.reminderIds = reminderIds; }
        public List<String> getBlindspotIds() { return blindspotIds; }
        public void setBlindspotIds(List<String> blindspotIds) { this.blindspotIds = blindspotIds; }
        public List<String> getMemoryKeys() { return memoryKeys; }
        public void setMemoryKeys(List<String> memoryKeys) { this.memoryKeys = memoryKeys; }
    }

    // Embedded: LlmMetrics
    public static class LlmMetrics {
        private String provider;
        private String model;
        private long promptTokens;
        private long completionTokens;
        private long totalTokens;
        private int llmCalls;
        private Double estimatedCostUsd;

        public LlmMetrics() {}

        public String getProvider() { return provider; }
        public void setProvider(String provider) { this.provider = provider; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        public long getPromptTokens() { return promptTokens; }
        public void setPromptTokens(long promptTokens) { this.promptTokens = promptTokens; }
        public long getCompletionTokens() { return completionTokens; }
        public void setCompletionTokens(long completionTokens) { this.completionTokens = completionTokens; }
        public long getTotalTokens() { return totalTokens; }
        public void setTotalTokens(long totalTokens) { this.totalTokens = totalTokens; }
        public int getLlmCalls() { return llmCalls; }
        public void setLlmCalls(int llmCalls) { this.llmCalls = llmCalls; }
        public Double getEstimatedCostUsd() { return estimatedCostUsd; }
        public void setEstimatedCostUsd(Double estimatedCostUsd) { this.estimatedCostUsd = estimatedCostUsd; }
    }

    // Embedded: ToolCallSummary
    public static class ToolCallSummary {
        private int totalCalls;
        private int readOnlyCalls;
        private int writeCalls;
        private int execCalls;
        private int failedCalls;

        public ToolCallSummary() {}

        public int getTotalCalls() { return totalCalls; }
        public void setTotalCalls(int totalCalls) { this.totalCalls = totalCalls; }
        public int getReadOnlyCalls() { return readOnlyCalls; }
        public void setReadOnlyCalls(int readOnlyCalls) { this.readOnlyCalls = readOnlyCalls; }
        public int getWriteCalls() { return writeCalls; }
        public void setWriteCalls(int writeCalls) { this.writeCalls = writeCalls; }
        public int getExecCalls() { return execCalls; }
        public void setExecCalls(int execCalls) { this.execCalls = execCalls; }
        public int getFailedCalls() { return failedCalls; }
        public void setFailedCalls(int failedCalls) { this.failedCalls = failedCalls; }
    }

    public String getPastExecutionId() { return pastExecutionId; }
    public void setPastExecutionId(String pastExecutionId) { this.pastExecutionId = pastExecutionId; }
    public String getExecutionId() { return executionId; }
    public void setExecutionId(String executionId) { this.executionId = executionId; }
    public String getAgentId() { return agentId; }
    public void setAgentId(String agentId) { this.agentId = agentId; }
    public String getProjectId() { return projectId; }
    public void setProjectId(String projectId) { this.projectId = projectId; }
    public String getScheduleId() { return scheduleId; }
    public void setScheduleId(String scheduleId) { this.scheduleId = scheduleId; }
    public Instant getScheduledAt() { return scheduledAt; }
    public void setScheduledAt(Instant scheduledAt) { this.scheduledAt = scheduledAt; }
    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }
    public Instant getEndedAt() { return endedAt; }
    public void setEndedAt(Instant endedAt) { this.endedAt = endedAt; }
    public long getDurationMs() { return durationMs; }
    public void setDurationMs(long durationMs) { this.durationMs = durationMs; }
    public ResultStatus getResultStatus() { return resultStatus; }
    public void setResultStatus(ResultStatus resultStatus) { this.resultStatus = resultStatus; }
    public String getErrorCode() { return errorCode; }
    public void setErrorCode(String errorCode) { this.errorCode = errorCode; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public String getResponseSummary() { return responseSummary; }
    public void setResponseSummary(String responseSummary) { this.responseSummary = responseSummary; }
    public ResponseRefs getResponseRefs() { return responseRefs; }
    public void setResponseRefs(ResponseRefs responseRefs) { this.responseRefs = responseRefs; }
    public LlmMetrics getLlmMetrics() { return llmMetrics; }
    public void setLlmMetrics(LlmMetrics llmMetrics) { this.llmMetrics = llmMetrics; }
    public ToolCallSummary getToolCallSummary() { return toolCallSummary; }
    public void setToolCallSummary(ToolCallSummary toolCallSummary) { this.toolCallSummary = toolCallSummary; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public String getThreadId() { return threadId; }
    public void setThreadId(String threadId) { this.threadId = threadId; }
    public int getAttempt() { return attempt; }
    public void setAttempt(int attempt) { this.attempt = attempt; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
