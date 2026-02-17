package io.github.drompincen.javaclawv1.persistence.document;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.index.TextIndexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.List;

@Document(collection = "designs")
public class DesignDocument {

    @Id
    private String designId;
    @Indexed
    private String projectId;
    @TextIndexed
    private String title;
    @Indexed
    private List<String> tags;
    private String source;
    private Object jsonBody;
    private Instant createdAt;
    private Instant updatedAt;
    @Version
    private int version;

    public DesignDocument() {}

    public String getDesignId() { return designId; }
    public void setDesignId(String designId) { this.designId = designId; }

    public String getProjectId() { return projectId; }
    public void setProjectId(String projectId) { this.projectId = projectId; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public List<String> getTags() { return tags; }
    public void setTags(List<String> tags) { this.tags = tags; }

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }

    public Object getJsonBody() { return jsonBody; }
    public void setJsonBody(Object jsonBody) { this.jsonBody = jsonBody; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    public int getVersion() { return version; }
    public void setVersion(int version) { this.version = version; }
}
