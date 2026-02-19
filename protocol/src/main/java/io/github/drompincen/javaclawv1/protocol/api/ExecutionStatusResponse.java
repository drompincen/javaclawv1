package io.github.drompincen.javaclawv1.protocol.api;

import java.time.Instant;

public record ExecutionStatusResponse(
        String executionId,
        String agentId,
        String projectId,
        Instant scheduledAt,
        int plannedHour,
        int plannedMinute,
        boolean immediate,
        ExecStatus execStatus,
        int attempt,
        String lockOwner
) {}
