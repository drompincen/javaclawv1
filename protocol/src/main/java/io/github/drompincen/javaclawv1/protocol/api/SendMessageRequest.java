package io.github.drompincen.javaclawv1.protocol.api;

import java.util.List;

public record SendMessageRequest(
        String content,
        String role,
        List<ContentPart> parts
) {
    public SendMessageRequest(String content) {
        this(content, "user", null);
    }

    public SendMessageRequest(String content, String role) {
        this(content, role, null);
    }

    public record ContentPart(String type, String text, String mediaType, String data) {}
}
