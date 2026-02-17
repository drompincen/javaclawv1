package io.github.drompincen.javaclawv1.protocol.api;

import java.time.Instant;
import java.util.List;

public record PlanDto(
        String planId,
        String projectId,
        String title,
        List<Milestone> milestones,
        List<String> ticketIds,
        Instant createdAt,
        Instant updatedAt
) {
    public record Milestone(
            String name,
            String description,
            List<String> ticketIds
    ) {}
}
