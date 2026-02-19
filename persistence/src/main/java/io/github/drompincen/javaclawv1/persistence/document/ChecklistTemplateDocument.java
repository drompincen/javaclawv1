package io.github.drompincen.javaclawv1.persistence.document;

import io.github.drompincen.javaclawv1.protocol.api.ChecklistCategory;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.List;

@Document(collection = "checklist_templates")
public class ChecklistTemplateDocument {

    @Id
    private String templateId;
    private String name;
    private String description;
    @Indexed
    private ChecklistCategory category;
    private List<TemplateItem> items;
    @Indexed
    private String projectId; // null = global template
    private Instant createdAt;
    private Instant updatedAt;

    public ChecklistTemplateDocument() {}

    public String getTemplateId() { return templateId; }
    public void setTemplateId(String templateId) { this.templateId = templateId; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public ChecklistCategory getCategory() { return category; }
    public void setCategory(ChecklistCategory category) { this.category = category; }

    public List<TemplateItem> getItems() { return items; }
    public void setItems(List<TemplateItem> items) { this.items = items; }

    public String getProjectId() { return projectId; }
    public void setProjectId(String projectId) { this.projectId = projectId; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    public static class TemplateItem {
        private String text;
        private String defaultAssigneeRole;
        private boolean required;
        private int sortOrder;

        public TemplateItem() {}

        public String getText() { return text; }
        public void setText(String text) { this.text = text; }
        public String getDefaultAssigneeRole() { return defaultAssigneeRole; }
        public void setDefaultAssigneeRole(String defaultAssigneeRole) { this.defaultAssigneeRole = defaultAssigneeRole; }
        public boolean isRequired() { return required; }
        public void setRequired(boolean required) { this.required = required; }
        public int getSortOrder() { return sortOrder; }
        public void setSortOrder(int sortOrder) { this.sortOrder = sortOrder; }
    }
}
