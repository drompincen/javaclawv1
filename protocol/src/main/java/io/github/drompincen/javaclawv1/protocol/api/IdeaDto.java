package io.github.drompincen.javaclawv1.protocol.api;

import java.time.Instant;
import java.util.List;

public record IdeaDto(
        String ideaId,
        String projectId,
        String title,
        String content,
        List<String> tags,
        IdeaStatus status,
        String promotedToTicketId,
        Instant createdAt,
        Instant updatedAt
) {
    public enum IdeaStatus {
        NEW, REVIEWED, PROMOTED, ARCHIVED
    }
}
