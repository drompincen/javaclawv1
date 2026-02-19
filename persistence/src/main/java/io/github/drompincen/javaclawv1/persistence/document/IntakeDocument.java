package io.github.drompincen.javaclawv1.persistence.document;

import io.github.drompincen.javaclawv1.protocol.api.IntakeSourceType;
import io.github.drompincen.javaclawv1.protocol.api.IntakeStatus;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Document(collection = "intakes")
@CompoundIndex(name = "project_status", def = "{'projectId': 1, 'status': 1}")
public class IntakeDocument {

    @Id
    private String intakeId;
    @Indexed
    private String projectId;
    @Indexed
    private IntakeSourceType sourceType;
    private String rawContentRef;
    private String classifiedAs;
    private Map<String, Object> extractedMetadata;
    private List<DispatchTarget> dispatchedTo;
    @Indexed
    private IntakeStatus status;
    private Instant createdAt;
    private Instant updatedAt;

    public IntakeDocument() {}

    public String getIntakeId() { return intakeId; }
    public void setIntakeId(String intakeId) { this.intakeId = intakeId; }

    public String getProjectId() { return projectId; }
    public void setProjectId(String projectId) { this.projectId = projectId; }

    public IntakeSourceType getSourceType() { return sourceType; }
    public void setSourceType(IntakeSourceType sourceType) { this.sourceType = sourceType; }

    public String getRawContentRef() { return rawContentRef; }
    public void setRawContentRef(String rawContentRef) { this.rawContentRef = rawContentRef; }

    public String getClassifiedAs() { return classifiedAs; }
    public void setClassifiedAs(String classifiedAs) { this.classifiedAs = classifiedAs; }

    public Map<String, Object> getExtractedMetadata() { return extractedMetadata; }
    public void setExtractedMetadata(Map<String, Object> extractedMetadata) { this.extractedMetadata = extractedMetadata; }

    public List<DispatchTarget> getDispatchedTo() { return dispatchedTo; }
    public void setDispatchedTo(List<DispatchTarget> dispatchedTo) { this.dispatchedTo = dispatchedTo; }

    public IntakeStatus getStatus() { return status; }
    public void setStatus(IntakeStatus status) { this.status = status; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    public static class DispatchTarget {
        private String agentId;
        private String sessionId;

        public DispatchTarget() {}

        public String getAgentId() { return agentId; }
        public void setAgentId(String agentId) { this.agentId = agentId; }
        public String getSessionId() { return sessionId; }
        public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    }
}
