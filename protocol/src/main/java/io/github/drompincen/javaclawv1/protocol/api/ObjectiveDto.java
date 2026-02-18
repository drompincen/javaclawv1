package io.github.drompincen.javaclawv1.protocol.api;

import java.time.Instant;
import java.util.List;

public record ObjectiveDto(
        String objectiveId,
        String projectId,
        String sprintName,
        String outcome,
        String measurableSignal,
        List<String> risks,
        List<String> threadIds,
        List<String> ticketIds,
        Double coveragePercent,
        ObjectiveStatus status,
        Instant startDate,
        Instant endDate,
        Instant createdAt,
        Instant updatedAt
) {}
