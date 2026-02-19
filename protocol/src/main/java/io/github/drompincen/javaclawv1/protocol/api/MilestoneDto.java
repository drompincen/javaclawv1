package io.github.drompincen.javaclawv1.protocol.api;

import java.time.Instant;
import java.util.List;

public record MilestoneDto(
        String milestoneId,
        String projectId,
        String name,
        String description,
        Instant targetDate,
        Instant actualDate,
        MilestoneStatus status,
        String phaseId,
        List<String> objectiveIds,
        List<String> ticketIds,
        String owner,
        List<String> dependencies,
        Instant createdAt,
        Instant updatedAt
) {}
