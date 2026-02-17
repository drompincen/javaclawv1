package io.github.drompincen.javaclawv1.protocol.api;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;

public record CheckpointDto(
        String checkpointId,
        String sessionId,
        int stepNo,
        Instant createdAt,
        JsonNode state,
        long eventOffset
) {}
