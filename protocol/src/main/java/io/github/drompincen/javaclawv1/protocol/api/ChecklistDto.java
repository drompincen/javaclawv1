package io.github.drompincen.javaclawv1.protocol.api;

import java.time.Instant;
import java.util.List;

public record ChecklistDto(
        String checklistId,
        String projectId,
        String name,
        String templateId,
        String phaseId,
        List<String> ticketIds,
        List<ChecklistItem> items,
        ChecklistStatus status,
        Instant createdAt,
        Instant updatedAt
) {
    public record ChecklistItem(
            String itemId,
            String text,
            String assignee,
            boolean checked,
            String notes,
            String linkedTicketId
    ) {}
}
