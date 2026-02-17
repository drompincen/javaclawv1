package io.github.drompincen.javaclawv1.protocol.api;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;

public record ApprovalRequestDto(
        String approvalId,
        String threadId,
        String toolName,
        JsonNode toolInput,
        ApprovalStatus status,
        Instant createdAt,
        Instant respondedAt
) {
    public enum ApprovalStatus {
        PENDING, APPROVED, DENIED
    }
}
