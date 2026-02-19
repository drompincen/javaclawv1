package io.github.drompincen.javaclawv1.protocol.api;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record BlindspotDto(
        String blindspotId,
        String projectId,
        String projectName,
        String title,
        String description,
        BlindspotCategory category,
        BlindspotSeverity severity,
        BlindspotStatus status,
        String owner,
        List<Map<String, String>> sourceRefs,
        String deltaPackId,
        String reconcileRunId,
        Instant createdAt,
        Instant updatedAt,
        Instant resolvedAt
) {}
