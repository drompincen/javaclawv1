package io.github.drompincen.javaclawv1.runtime.tools;

import com.fasterxml.jackson.databind.JsonNode;

public record ToolResult(
        boolean success,
        JsonNode output,
        String error
) {
    public static ToolResult success(JsonNode output) {
        return new ToolResult(true, output, null);
    }

    public static ToolResult failure(String error) {
        return new ToolResult(false, null, error);
    }
}
