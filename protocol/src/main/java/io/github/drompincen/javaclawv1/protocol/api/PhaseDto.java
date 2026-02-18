package io.github.drompincen.javaclawv1.protocol.api;

import java.time.Instant;
import java.util.List;

public record PhaseDto(
        String phaseId,
        String projectId,
        String name,
        String description,
        List<String> entryCriteria,
        List<String> exitCriteria,
        List<String> checklistIds,
        List<String> objectiveIds,
        PhaseStatus status,
        int sortOrder,
        Instant startDate,
        Instant endDate,
        Instant createdAt,
        Instant updatedAt
) {}
