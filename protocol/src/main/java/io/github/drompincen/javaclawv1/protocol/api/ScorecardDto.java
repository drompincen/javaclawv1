package io.github.drompincen.javaclawv1.protocol.api;

import java.time.Instant;
import java.util.Map;

public record ScorecardDto(
        String scorecardId,
        String projectId,
        Map<String, Object> metrics,
        HealthStatus health,
        Instant updatedAt
) {
    public enum HealthStatus {
        GREEN, YELLOW, RED
    }
}
