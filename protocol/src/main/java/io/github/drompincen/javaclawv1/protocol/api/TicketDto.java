package io.github.drompincen.javaclawv1.protocol.api;

import java.time.Instant;
import java.util.List;

public record TicketDto(
        String ticketId,
        String projectId,
        String title,
        String description,
        TicketStatus status,
        TicketPriority priority,
        String assignedResourceId,
        List<String> linkedThreadIds,
        List<String> blockedBy,
        Instant createdAt,
        Instant updatedAt
) {
    public enum TicketStatus {
        TODO, IN_PROGRESS, REVIEW, DONE, BLOCKED
    }

    public enum TicketPriority {
        LOW, MEDIUM, HIGH, CRITICAL
    }
}
