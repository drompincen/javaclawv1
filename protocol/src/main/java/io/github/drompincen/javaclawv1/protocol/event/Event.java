package io.github.drompincen.javaclawv1.protocol.event;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;

public record Event(
        String eventId,
        String sessionId,
        long seq,
        EventType type,
        JsonNode payload,
        Instant timestamp
) {}
