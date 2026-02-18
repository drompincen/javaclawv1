package io.github.drompincen.javaclawv1.runtime.agent.llm;

import java.util.List;

public record ScenarioConfig(String projectName, String description, List<ScenarioStep> steps) {

    public record ScenarioStep(String userQuery, String description, List<AgentResponse> agentResponses) {}

    public record AgentResponse(String agentName, String responseFallback) {}
}
