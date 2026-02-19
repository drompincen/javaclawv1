package io.github.drompincen.javaclawv1.protocol.api;

import java.time.Instant;

public record ScheduleResponse(
        String scheduleId,
        String agentId,
        boolean enabled,
        ScheduleType scheduleType,
        ProjectScope projectScope,
        String projectId,
        Instant nextExecutionAt,
        long version,
        Instant createdAt,
        Instant updatedAt
) {}
