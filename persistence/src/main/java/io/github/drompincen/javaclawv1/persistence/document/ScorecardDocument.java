package io.github.drompincen.javaclawv1.persistence.document;

import io.github.drompincen.javaclawv1.protocol.api.ScorecardDto;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.Map;

@Document(collection = "scorecards")
public class ScorecardDocument {

    @Id
    private String scorecardId;
    @Indexed(unique = true)
    private String projectId;
    private Map<String, Object> metrics;
    private ScorecardDto.HealthStatus health;
    private Instant updatedAt;

    public ScorecardDocument() {}

    public String getScorecardId() { return scorecardId; }
    public void setScorecardId(String scorecardId) { this.scorecardId = scorecardId; }

    public String getProjectId() { return projectId; }
    public void setProjectId(String projectId) { this.projectId = projectId; }

    public Map<String, Object> getMetrics() { return metrics; }
    public void setMetrics(Map<String, Object> metrics) { this.metrics = metrics; }

    public ScorecardDto.HealthStatus getHealth() { return health; }
    public void setHealth(ScorecardDto.HealthStatus health) { this.health = health; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
