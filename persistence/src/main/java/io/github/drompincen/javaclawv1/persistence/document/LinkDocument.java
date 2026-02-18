package io.github.drompincen.javaclawv1.persistence.document;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.List;

@Document(collection = "links")
@CompoundIndex(name = "project_category", def = "{'projectId': 1, 'category': 1}")
@CompoundIndex(name = "project_pinned", def = "{'projectId': 1, 'pinned': -1}")
public class LinkDocument {

    @Id
    private String linkId;
    @Indexed
    private String projectId;
    private String url;
    private String title;
    private String category;
    private String description;
    private boolean pinned;
    @Indexed
    private String bundleId;
    private List<String> threadIds;
    private List<String> objectiveIds;
    private List<String> phaseIds;
    private List<String> tags;
    private Instant createdAt;
    private Instant updatedAt;

    public LinkDocument() {}

    public String getLinkId() { return linkId; }
    public void setLinkId(String linkId) { this.linkId = linkId; }

    public String getProjectId() { return projectId; }
    public void setProjectId(String projectId) { this.projectId = projectId; }

    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public boolean isPinned() { return pinned; }
    public void setPinned(boolean pinned) { this.pinned = pinned; }

    public String getBundleId() { return bundleId; }
    public void setBundleId(String bundleId) { this.bundleId = bundleId; }

    public List<String> getThreadIds() { return threadIds; }
    public void setThreadIds(List<String> threadIds) { this.threadIds = threadIds; }

    public List<String> getObjectiveIds() { return objectiveIds; }
    public void setObjectiveIds(List<String> objectiveIds) { this.objectiveIds = objectiveIds; }

    public List<String> getPhaseIds() { return phaseIds; }
    public void setPhaseIds(List<String> phaseIds) { this.phaseIds = phaseIds; }

    public List<String> getTags() { return tags; }
    public void setTags(List<String> tags) { this.tags = tags; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
