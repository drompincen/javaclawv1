package io.github.drompincen.javaclawv1.runtime.agent.llm;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

public record ScenarioConfigV2(
        int schemaVersion,
        String projectName,
        String description,
        Defaults defaults,
        List<Step> steps
) {
    public record Defaults(Long maxWaitMs, Boolean requireCheckerPass) {}

    public record Step(
            String name,
            String type,
            String userQuery,
            String description,
            List<ScenarioConfig.AgentResponse> agentResponses,
            List<ToolMock> toolMocks,
            StepExpectations expects
    ) {}

    public record ToolMock(String tool, ToolMockMatch match, ToolMockResult result) {}

    public record ToolMockMatch(String type, List<String> keys, String json, String pattern) {}

    public record ToolMockResult(boolean success, Object output, String error) {}

    public record StepExpectations(
            String sessionStatus,
            EventExpectations events,
            List<MongoAssertion> mongo,
            MessageExpectations messages
    ) {}

    public record EventExpectations(
            List<String> containsTypes,
            Map<String, Integer> minCounts
    ) {}

    public record MongoAssertion(
            String collection,
            Map<String, Object> filter,
            @JsonProperty("assert") AssertCondition assertCondition
    ) {}

    public record AssertCondition(
            Integer countEq,
            Integer countGte,
            Integer countLte,
            Boolean exists,
            String anyMatchField,
            String anyMatchPattern
    ) {}

    public record MessageExpectations(
            String lastAssistantContains,
            String lastAssistantMatches,
            String anyAssistantContains
    ) {}
}
