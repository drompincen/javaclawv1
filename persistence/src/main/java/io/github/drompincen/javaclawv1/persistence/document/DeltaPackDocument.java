package io.github.drompincen.javaclawv1.persistence.document;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Document(collection = "delta_packs")
@CompoundIndex(name = "project_status", def = "{'projectId': 1, 'status': 1}")
public class DeltaPackDocument {

    @Id
    private String deltaPackId;
    @Indexed
    private String projectId;
    private String projectName;
    private String reconcileSessionId;
    private List<Map<String, Object>> sourcesCompared;
    private List<Delta> deltas;
    private Map<String, Object> summary;
    private String reportArtifactPath;
    private String status; // DRAFT, FINAL, SUPERSEDED
    private Instant createdAt;
    private Instant updatedAt;

    public DeltaPackDocument() {}

    public String getDeltaPackId() { return deltaPackId; }
    public void setDeltaPackId(String deltaPackId) { this.deltaPackId = deltaPackId; }
    public String getProjectId() { return projectId; }
    public void setProjectId(String projectId) { this.projectId = projectId; }
    public String getProjectName() { return projectName; }
    public void setProjectName(String projectName) { this.projectName = projectName; }
    public String getReconcileSessionId() { return reconcileSessionId; }
    public void setReconcileSessionId(String reconcileSessionId) { this.reconcileSessionId = reconcileSessionId; }
    public List<Map<String, Object>> getSourcesCompared() { return sourcesCompared; }
    public void setSourcesCompared(List<Map<String, Object>> sourcesCompared) { this.sourcesCompared = sourcesCompared; }
    public List<Delta> getDeltas() { return deltas; }
    public void setDeltas(List<Delta> deltas) { this.deltas = deltas; }
    public Map<String, Object> getSummary() { return summary; }
    public void setSummary(Map<String, Object> summary) { this.summary = summary; }
    public String getReportArtifactPath() { return reportArtifactPath; }
    public void setReportArtifactPath(String reportArtifactPath) { this.reportArtifactPath = reportArtifactPath; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    public static class Delta {
        private String deltaType;
        private String severity;
        private String title;
        private String description;
        private String sourceA;
        private String sourceB;
        private String fieldName;
        private String valueA;
        private String valueB;
        private String suggestedAction;
        private boolean autoResolvable;

        public Delta() {}

        public String getDeltaType() { return deltaType; }
        public void setDeltaType(String deltaType) { this.deltaType = deltaType; }
        public String getSeverity() { return severity; }
        public void setSeverity(String severity) { this.severity = severity; }
        public String getTitle() { return title; }
        public void setTitle(String title) { this.title = title; }
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
        public String getSourceA() { return sourceA; }
        public void setSourceA(String sourceA) { this.sourceA = sourceA; }
        public String getSourceB() { return sourceB; }
        public void setSourceB(String sourceB) { this.sourceB = sourceB; }
        public String getFieldName() { return fieldName; }
        public void setFieldName(String fieldName) { this.fieldName = fieldName; }
        public String getValueA() { return valueA; }
        public void setValueA(String valueA) { this.valueA = valueA; }
        public String getValueB() { return valueB; }
        public void setValueB(String valueB) { this.valueB = valueB; }
        public String getSuggestedAction() { return suggestedAction; }
        public void setSuggestedAction(String suggestedAction) { this.suggestedAction = suggestedAction; }
        public boolean isAutoResolvable() { return autoResolvable; }
        public void setAutoResolvable(boolean autoResolvable) { this.autoResolvable = autoResolvable; }
    }
}
