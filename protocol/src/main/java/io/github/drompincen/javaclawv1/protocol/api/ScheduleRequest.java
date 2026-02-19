package io.github.drompincen.javaclawv1.protocol.api;

import java.util.List;

public record ScheduleRequest(
        String agentId,
        Boolean enabled,
        String timezone,
        ScheduleType scheduleType,
        String cronExpr,
        List<String> timesOfDay,
        Integer intervalMinutes,
        ProjectScope projectScope,
        String projectId
) {}
