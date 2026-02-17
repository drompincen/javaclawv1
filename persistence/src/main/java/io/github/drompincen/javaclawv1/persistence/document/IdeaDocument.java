package io.github.drompincen.javaclawv1.persistence.document;

import io.github.drompincen.javaclawv1.protocol.api.IdeaDto;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.List;

@Document(collection = "ideas")
@CompoundIndex(name = "project_tags", def = "{'projectId': 1, 'tags': 1}")
public class IdeaDocument {

    @Id
    private String ideaId;
    private String projectId;
    private String title;
    private String content;
    private List<String> tags;
    private IdeaDto.IdeaStatus status;
    private String promotedToTicketId;
    private Instant createdAt;
    private Instant updatedAt;

    public IdeaDocument() {}

    public String getIdeaId() { return ideaId; }
    public void setIdeaId(String ideaId) { this.ideaId = ideaId; }

    public String getProjectId() { return projectId; }
    public void setProjectId(String projectId) { this.projectId = projectId; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public List<String> getTags() { return tags; }
    public void setTags(List<String> tags) { this.tags = tags; }

    public IdeaDto.IdeaStatus getStatus() { return status; }
    public void setStatus(IdeaDto.IdeaStatus status) { this.status = status; }

    public String getPromotedToTicketId() { return promotedToTicketId; }
    public void setPromotedToTicketId(String promotedToTicketId) { this.promotedToTicketId = promotedToTicketId; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
