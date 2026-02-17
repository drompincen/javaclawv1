package io.github.drompincen.javaclawv1.protocol.api;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record ProjectDto(
        String projectId,
        String name,
        String description,
        ProjectStatus status,
        List<String> tags,
        Map<String, String> metadata,
        Instant createdAt,
        Instant updatedAt
) {
    public enum ProjectStatus {
        ACTIVE, ARCHIVED, TEMPLATE
    }
}
