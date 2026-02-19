package io.github.drompincen.javaclawv1.protocol.ws;

public enum WsMessageType {
    // Client -> Server
    SUBSCRIBE_SESSION,
    SUBSCRIBE_PROJECT,
    SUBSCRIBE_SPECS,
    SUBSCRIBE_TOOLS,
    UNSUBSCRIBE,
    SEND_MESSAGE,
    APPROVE_TOOL_CALL,
    DENY_TOOL_CALL,

    // Server -> Client
    EVENT,
    SESSION_UPDATE,
    SPEC_UPDATE,
    TOOL_UPDATE,
    ERROR,
    SUBSCRIBED,
    UNSUBSCRIBED
}
