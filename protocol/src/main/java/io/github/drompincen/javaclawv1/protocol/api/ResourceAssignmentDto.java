package io.github.drompincen.javaclawv1.protocol.api;

public record ResourceAssignmentDto(
        String assignmentId,
        String resourceId,
        String ticketId,
        double percentageAllocation
) {}
