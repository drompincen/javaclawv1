package io.github.drompincen.javaclawv1.protocol.api;

import java.time.Instant;
import java.util.List;

public record ReconciliationDto(
        String reconciliationId,
        String projectId,
        String sourceUploadId,
        String sourceType,
        List<MappingEntry> mappings,
        List<ConflictEntry> conflicts,
        ReconciliationStatus status,
        Instant createdAt,
        Instant updatedAt
) {
    public record MappingEntry(
            String sourceRow,
            String ticketId,
            String matchType
    ) {}

    public record ConflictEntry(
            String field,
            String sourceValue,
            String ticketValue,
            String resolution
    ) {}
}
