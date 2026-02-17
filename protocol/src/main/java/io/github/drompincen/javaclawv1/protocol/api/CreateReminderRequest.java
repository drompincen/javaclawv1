package io.github.drompincen.javaclawv1.protocol.api;

import java.time.Instant;

public record CreateReminderRequest(
        String sessionId,
        String message,
        ReminderDto.ReminderType type,
        Instant triggerAt,
        boolean recurring,
        Long intervalSeconds
) {}
