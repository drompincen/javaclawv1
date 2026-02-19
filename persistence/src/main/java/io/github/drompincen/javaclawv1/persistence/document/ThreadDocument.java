package io.github.drompincen.javaclawv1.persistence.document;

import io.github.drompincen.javaclawv1.protocol.api.ModelConfig;
import io.github.drompincen.javaclawv1.protocol.api.SessionStatus;
import io.github.drompincen.javaclawv1.protocol.api.ThreadLifecycle;
import io.github.drompincen.javaclawv1.protocol.api.ToolPolicy;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.List;

@Document(collection = "threads")
@CompoundIndex(name = "project_updated", def = "{'projectIds': 1, 'updatedAt': -1}")
public class ThreadDocument {

    @Id
    private String threadId;
    private List<String> projectIds;
    @Deprecated
    private String projectId;
    private String title;
    @Deprecated
    private SessionStatus status;
    private ThreadLifecycle lifecycle;
    private ModelConfig modelConfig;
    private ToolPolicy toolPolicy;
    private String currentCheckpointId;
    private String summary;
    private List<EvidenceRef> evidence;
    private List<Decision> decisions;
    private List<ActionItem> actions;
    private List<String> extractedIdeas;
    private List<String> objectiveIds;
    private List<String> phaseIds;
    private String namingPolicy;
    private List<String> mergedFromThreadIds;
    private String mergedIntoThreadId;
    private Instant lastExtractedAt;
    private int extractionCount;
    private Instant createdAt;
    private Instant updatedAt;

    public ThreadDocument() {}

    public String getThreadId() { return threadId; }
    public void setThreadId(String threadId) { this.threadId = threadId; }

    public List<String> getProjectIds() { return projectIds; }
    public void setProjectIds(List<String> projectIds) { this.projectIds = projectIds; }

    @Deprecated
    public String getProjectId() { return projectId; }
    @Deprecated
    public void setProjectId(String projectId) { this.projectId = projectId; }

    public List<String> getEffectiveProjectIds() {
        if (projectIds != null && !projectIds.isEmpty()) return projectIds;
        return projectId != null ? List.of(projectId) : List.of();
    }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    @Deprecated
    public SessionStatus getStatus() { return status; }
    @Deprecated
    public void setStatus(SessionStatus status) { this.status = status; }

    public ThreadLifecycle getLifecycle() { return lifecycle; }
    public void setLifecycle(ThreadLifecycle lifecycle) { this.lifecycle = lifecycle; }

    public ModelConfig getModelConfig() { return modelConfig; }
    public void setModelConfig(ModelConfig modelConfig) { this.modelConfig = modelConfig; }

    public ToolPolicy getToolPolicy() { return toolPolicy; }
    public void setToolPolicy(ToolPolicy toolPolicy) { this.toolPolicy = toolPolicy; }

    public String getCurrentCheckpointId() { return currentCheckpointId; }
    public void setCurrentCheckpointId(String id) { this.currentCheckpointId = id; }

    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }

    public List<EvidenceRef> getEvidence() { return evidence; }
    public void setEvidence(List<EvidenceRef> evidence) { this.evidence = evidence; }

    public List<Decision> getDecisions() { return decisions; }
    public void setDecisions(List<Decision> decisions) { this.decisions = decisions; }

    public List<ActionItem> getActions() { return actions; }
    public void setActions(List<ActionItem> actions) { this.actions = actions; }

    public List<String> getExtractedIdeas() { return extractedIdeas; }
    public void setExtractedIdeas(List<String> extractedIdeas) { this.extractedIdeas = extractedIdeas; }

    public List<String> getObjectiveIds() { return objectiveIds; }
    public void setObjectiveIds(List<String> objectiveIds) { this.objectiveIds = objectiveIds; }

    public List<String> getPhaseIds() { return phaseIds; }
    public void setPhaseIds(List<String> phaseIds) { this.phaseIds = phaseIds; }

    public String getNamingPolicy() { return namingPolicy; }
    public void setNamingPolicy(String namingPolicy) { this.namingPolicy = namingPolicy; }

    public List<String> getMergedFromThreadIds() { return mergedFromThreadIds; }
    public void setMergedFromThreadIds(List<String> mergedFromThreadIds) { this.mergedFromThreadIds = mergedFromThreadIds; }

    public String getMergedIntoThreadId() { return mergedIntoThreadId; }
    public void setMergedIntoThreadId(String mergedIntoThreadId) { this.mergedIntoThreadId = mergedIntoThreadId; }

    public Instant getLastExtractedAt() { return lastExtractedAt; }
    public void setLastExtractedAt(Instant lastExtractedAt) { this.lastExtractedAt = lastExtractedAt; }

    public int getExtractionCount() { return extractionCount; }
    public void setExtractionCount(int extractionCount) { this.extractionCount = extractionCount; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    public static class EvidenceRef {
        private String uploadId;
        private String snippet;
        private double relevance;

        public EvidenceRef() {}

        public String getUploadId() { return uploadId; }
        public void setUploadId(String uploadId) { this.uploadId = uploadId; }
        public String getSnippet() { return snippet; }
        public void setSnippet(String snippet) { this.snippet = snippet; }
        public double getRelevance() { return relevance; }
        public void setRelevance(double relevance) { this.relevance = relevance; }
    }

    public static class Decision {
        private String text;
        private Instant date;
        private String decidedBy;
        private String context;

        public Decision() {}

        public String getText() { return text; }
        public void setText(String text) { this.text = text; }
        public Instant getDate() { return date; }
        public void setDate(Instant date) { this.date = date; }
        public String getDecidedBy() { return decidedBy; }
        public void setDecidedBy(String decidedBy) { this.decidedBy = decidedBy; }
        public String getContext() { return context; }
        public void setContext(String context) { this.context = context; }
    }

    public static class ActionItem {
        private String text;
        private String assignee;
        private Instant dueDate;
        private String ticketId;
        private String status;

        public ActionItem() {}

        public String getText() { return text; }
        public void setText(String text) { this.text = text; }
        public String getAssignee() { return assignee; }
        public void setAssignee(String assignee) { this.assignee = assignee; }
        public Instant getDueDate() { return dueDate; }
        public void setDueDate(Instant dueDate) { this.dueDate = dueDate; }
        public String getTicketId() { return ticketId; }
        public void setTicketId(String ticketId) { this.ticketId = ticketId; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
    }
}
