package io.github.drompincen.javaclawv1.protocol.api;

import java.time.Instant;

public record ReminderDto(
        String reminderId,
        String projectId,
        String message,
        ReminderType type,
        Instant triggerAt,
        String condition,
        boolean triggered,
        boolean recurring,
        Long intervalSeconds
) {
    public enum ReminderType {
        TIME_BASED, CONDITION_BASED
    }
}
