package io.github.drompincen.javaclawv1.protocol.api;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record DeltaPackDto(
        String deltaPackId,
        String projectId,
        String projectName,
        String reconcileSessionId,
        List<Map<String, Object>> sourcesCompared,
        List<DeltaEntry> deltas,
        Map<String, Object> summary,
        String status,
        Instant createdAt,
        Instant updatedAt
) {
    public record DeltaEntry(
            String deltaType,
            String severity,
            String title,
            String description,
            String sourceA,
            String sourceB,
            String fieldName,
            String valueA,
            String valueB,
            String suggestedAction,
            boolean autoResolvable
    ) {}
}
