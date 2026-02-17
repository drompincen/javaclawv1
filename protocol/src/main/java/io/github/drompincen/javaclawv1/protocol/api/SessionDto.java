package io.github.drompincen.javaclawv1.protocol.api;

import java.time.Instant;
import java.util.Map;

public record SessionDto(
        String sessionId,
        String threadId,
        Instant createdAt,
        Instant updatedAt,
        SessionStatus status,
        ModelConfig modelConfig,
        ToolPolicy toolPolicy,
        String currentCheckpointId,
        Map<String, String> metadata
) {}
