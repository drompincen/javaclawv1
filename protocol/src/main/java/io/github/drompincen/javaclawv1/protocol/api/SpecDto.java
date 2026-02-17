package io.github.drompincen.javaclawv1.protocol.api;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;

public record SpecDto(
        String specId,
        String title,
        List<String> tags,
        String source,
        JsonNode jsonBody,
        Instant createdAt,
        Instant updatedAt,
        int version
) {}
