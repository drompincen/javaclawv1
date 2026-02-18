package io.github.drompincen.javaclawv1.persistence.document;

import io.github.drompincen.javaclawv1.protocol.api.ReconciliationStatus;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.List;

@Document(collection = "reconciliations")
@CompoundIndex(name = "project_status", def = "{'projectId': 1, 'status': 1}")
public class ReconciliationDocument {

    @Id
    private String reconciliationId;
    @Indexed
    private String projectId;
    @Indexed
    private String sourceUploadId;
    private String sourceType;
    private List<MappingEntry> mappings;
    private List<ConflictEntry> conflicts;
    private ReconciliationStatus status;
    private Instant createdAt;
    private Instant updatedAt;

    public ReconciliationDocument() {}

    public String getReconciliationId() { return reconciliationId; }
    public void setReconciliationId(String reconciliationId) { this.reconciliationId = reconciliationId; }

    public String getProjectId() { return projectId; }
    public void setProjectId(String projectId) { this.projectId = projectId; }

    public String getSourceUploadId() { return sourceUploadId; }
    public void setSourceUploadId(String sourceUploadId) { this.sourceUploadId = sourceUploadId; }

    public String getSourceType() { return sourceType; }
    public void setSourceType(String sourceType) { this.sourceType = sourceType; }

    public List<MappingEntry> getMappings() { return mappings; }
    public void setMappings(List<MappingEntry> mappings) { this.mappings = mappings; }

    public List<ConflictEntry> getConflicts() { return conflicts; }
    public void setConflicts(List<ConflictEntry> conflicts) { this.conflicts = conflicts; }

    public ReconciliationStatus getStatus() { return status; }
    public void setStatus(ReconciliationStatus status) { this.status = status; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    public static class MappingEntry {
        private String sourceRow;
        private String ticketId;
        private String matchType;

        public MappingEntry() {}

        public String getSourceRow() { return sourceRow; }
        public void setSourceRow(String sourceRow) { this.sourceRow = sourceRow; }
        public String getTicketId() { return ticketId; }
        public void setTicketId(String ticketId) { this.ticketId = ticketId; }
        public String getMatchType() { return matchType; }
        public void setMatchType(String matchType) { this.matchType = matchType; }
    }

    public static class ConflictEntry {
        private String field;
        private String sourceValue;
        private String ticketValue;
        private String resolution;

        public ConflictEntry() {}

        public String getField() { return field; }
        public void setField(String field) { this.field = field; }
        public String getSourceValue() { return sourceValue; }
        public void setSourceValue(String sourceValue) { this.sourceValue = sourceValue; }
        public String getTicketValue() { return ticketValue; }
        public void setTicketValue(String ticketValue) { this.ticketValue = ticketValue; }
        public String getResolution() { return resolution; }
        public void setResolution(String resolution) { this.resolution = resolution; }
    }
}
