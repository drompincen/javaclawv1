package io.github.drompincen.javaclawv1.protocol.api;

public record ExtractionResponse(
        String extractionId,
        String sessionId,
        String status,
        ExtractionSummary summary
) {}
