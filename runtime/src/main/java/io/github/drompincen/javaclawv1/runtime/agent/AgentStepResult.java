package io.github.drompincen.javaclawv1.runtime.agent;

import com.fasterxml.jackson.databind.JsonNode;

public record AgentStepResult(
        String response,
        String toolCallName,
        JsonNode toolCallInput,
        boolean done,
        String stopReason
) {
    public static AgentStepResult reply(String text) {
        return new AgentStepResult(text, null, null, false, null);
    }

    public static AgentStepResult toolCall(String name, JsonNode input) {
        return new AgentStepResult(null, name, input, false, null);
    }

    public static AgentStepResult stop(String reason) {
        return new AgentStepResult(null, null, null, true, reason);
    }
}
