package io.github.drompincen.javaclawv1.protocol.api;

import java.time.Instant;

public record PastExecutionResponse(
        String pastExecutionId,
        String agentId,
        String projectId,
        Instant scheduledAt,
        Instant startedAt,
        Instant endedAt,
        long durationMs,
        ResultStatus resultStatus,
        String errorMessage,
        String responseSummary,
        int attempt
) {}
