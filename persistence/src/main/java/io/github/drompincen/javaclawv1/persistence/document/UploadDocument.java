package io.github.drompincen.javaclawv1.persistence.document;

import io.github.drompincen.javaclawv1.protocol.api.UploadStatus;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.index.TextIndexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Document(collection = "uploads")
@CompoundIndex(name = "project_status", def = "{'projectId': 1, 'status': 1}")
@CompoundIndex(name = "project_timestamp", def = "{'projectId': 1, 'contentTimestamp': -1}")
public class UploadDocument {

    @Id
    private String uploadId;
    @Indexed
    private String projectId;
    private String source;
    private String sourceUrl;
    @TextIndexed
    private String title;
    @TextIndexed
    private String content;
    private String contentType;
    private String binaryRef;
    private List<String> tags;
    private List<String> people;
    private List<String> systems;
    private Instant contentTimestamp;
    private Instant inferredTimestamp;
    @Indexed
    private String threadId;
    private Double confidence;
    private UploadStatus status;
    private Map<String, String> metadata;
    private Instant createdAt;
    private Instant updatedAt;

    public UploadDocument() {}

    public String getUploadId() { return uploadId; }
    public void setUploadId(String uploadId) { this.uploadId = uploadId; }

    public String getProjectId() { return projectId; }
    public void setProjectId(String projectId) { this.projectId = projectId; }

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }

    public String getSourceUrl() { return sourceUrl; }
    public void setSourceUrl(String sourceUrl) { this.sourceUrl = sourceUrl; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public String getContentType() { return contentType; }
    public void setContentType(String contentType) { this.contentType = contentType; }

    public String getBinaryRef() { return binaryRef; }
    public void setBinaryRef(String binaryRef) { this.binaryRef = binaryRef; }

    public List<String> getTags() { return tags; }
    public void setTags(List<String> tags) { this.tags = tags; }

    public List<String> getPeople() { return people; }
    public void setPeople(List<String> people) { this.people = people; }

    public List<String> getSystems() { return systems; }
    public void setSystems(List<String> systems) { this.systems = systems; }

    public Instant getContentTimestamp() { return contentTimestamp; }
    public void setContentTimestamp(Instant contentTimestamp) { this.contentTimestamp = contentTimestamp; }

    public Instant getInferredTimestamp() { return inferredTimestamp; }
    public void setInferredTimestamp(Instant inferredTimestamp) { this.inferredTimestamp = inferredTimestamp; }

    public String getThreadId() { return threadId; }
    public void setThreadId(String threadId) { this.threadId = threadId; }

    public Double getConfidence() { return confidence; }
    public void setConfidence(Double confidence) { this.confidence = confidence; }

    public UploadStatus getStatus() { return status; }
    public void setStatus(UploadStatus status) { this.status = status; }

    public Map<String, String> getMetadata() { return metadata; }
    public void setMetadata(Map<String, String> metadata) { this.metadata = metadata; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
