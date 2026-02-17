package io.github.drompincen.javaclawv1.protocol.api;

import java.time.Instant;
import java.util.List;

public record ThreadDto(
        String threadId,
        List<String> projectIds,
        String title,
        SessionStatus status,
        ModelConfig modelConfig,
        ToolPolicy toolPolicy,
        String currentCheckpointId,
        Instant createdAt,
        Instant updatedAt
) {}
