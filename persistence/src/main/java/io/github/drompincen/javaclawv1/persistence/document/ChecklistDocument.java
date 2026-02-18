package io.github.drompincen.javaclawv1.persistence.document;

import io.github.drompincen.javaclawv1.protocol.api.ChecklistStatus;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.List;

@Document(collection = "checklists")
@CompoundIndex(name = "project_status", def = "{'projectId': 1, 'status': 1}")
public class ChecklistDocument {

    @Id
    private String checklistId;
    @Indexed
    private String projectId;
    private String name;
    private String templateId;
    @Indexed
    private String phaseId;
    private List<String> ticketIds;
    private List<ChecklistItem> items;
    private ChecklistStatus status;
    private Instant createdAt;
    private Instant updatedAt;

    public ChecklistDocument() {}

    public String getChecklistId() { return checklistId; }
    public void setChecklistId(String checklistId) { this.checklistId = checklistId; }

    public String getProjectId() { return projectId; }
    public void setProjectId(String projectId) { this.projectId = projectId; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getTemplateId() { return templateId; }
    public void setTemplateId(String templateId) { this.templateId = templateId; }

    public String getPhaseId() { return phaseId; }
    public void setPhaseId(String phaseId) { this.phaseId = phaseId; }

    public List<String> getTicketIds() { return ticketIds; }
    public void setTicketIds(List<String> ticketIds) { this.ticketIds = ticketIds; }

    public List<ChecklistItem> getItems() { return items; }
    public void setItems(List<ChecklistItem> items) { this.items = items; }

    public ChecklistStatus getStatus() { return status; }
    public void setStatus(ChecklistStatus status) { this.status = status; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    public static class ChecklistItem {
        private String itemId;
        private String text;
        private String assignee;
        private boolean checked;
        private String notes;
        private String linkedTicketId;

        public ChecklistItem() {}

        public String getItemId() { return itemId; }
        public void setItemId(String itemId) { this.itemId = itemId; }
        public String getText() { return text; }
        public void setText(String text) { this.text = text; }
        public String getAssignee() { return assignee; }
        public void setAssignee(String assignee) { this.assignee = assignee; }
        public boolean isChecked() { return checked; }
        public void setChecked(boolean checked) { this.checked = checked; }
        public String getNotes() { return notes; }
        public void setNotes(String notes) { this.notes = notes; }
        public String getLinkedTicketId() { return linkedTicketId; }
        public void setLinkedTicketId(String linkedTicketId) { this.linkedTicketId = linkedTicketId; }
    }
}
