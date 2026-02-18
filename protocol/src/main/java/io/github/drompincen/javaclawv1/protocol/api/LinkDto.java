package io.github.drompincen.javaclawv1.protocol.api;

import java.time.Instant;
import java.util.List;

public record LinkDto(
        String linkId,
        String projectId,
        String url,
        String title,
        String category,
        String description,
        boolean pinned,
        String bundleId,
        List<String> threadIds,
        List<String> objectiveIds,
        List<String> phaseIds,
        List<String> tags,
        Instant createdAt,
        Instant updatedAt
) {}
