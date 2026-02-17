package io.github.drompincen.javaclawv1.persistence.document;

import io.github.drompincen.javaclawv1.protocol.api.PlanDto;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.List;

@Document(collection = "plans")
public class PlanDocument {

    @Id
    private String planId;
    @Indexed
    private String projectId;
    private String title;
    private List<PlanDto.Milestone> milestones;
    private List<String> ticketIds;
    private Instant createdAt;
    private Instant updatedAt;

    public PlanDocument() {}

    public String getPlanId() { return planId; }
    public void setPlanId(String planId) { this.planId = planId; }

    public String getProjectId() { return projectId; }
    public void setProjectId(String projectId) { this.projectId = projectId; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public List<PlanDto.Milestone> getMilestones() { return milestones; }
    public void setMilestones(List<PlanDto.Milestone> milestones) { this.milestones = milestones; }

    public List<String> getTicketIds() { return ticketIds; }
    public void setTicketIds(List<String> ticketIds) { this.ticketIds = ticketIds; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
