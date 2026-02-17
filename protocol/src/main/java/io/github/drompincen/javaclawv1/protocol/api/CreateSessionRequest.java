package io.github.drompincen.javaclawv1.protocol.api;

import java.util.Map;

public record CreateSessionRequest(
        ModelConfig modelConfig,
        ToolPolicy toolPolicy,
        Map<String, String> metadata
) {}
