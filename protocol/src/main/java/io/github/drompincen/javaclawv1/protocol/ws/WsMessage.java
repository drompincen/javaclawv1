package io.github.drompincen.javaclawv1.protocol.ws;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;

public record WsMessage(
        WsMessageType type,
        String sessionId,
        JsonNode payload,
        Instant ts
) {
    public static WsMessage of(WsMessageType type, String sessionId, JsonNode payload) {
        return new WsMessage(type, sessionId, payload, Instant.now());
    }

    public static WsMessage error(String sessionId, JsonNode payload) {
        return of(WsMessageType.ERROR, sessionId, payload);
    }
}
