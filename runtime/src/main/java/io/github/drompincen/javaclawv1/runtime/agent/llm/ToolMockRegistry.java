package io.github.drompincen.javaclawv1.runtime.agent.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.drompincen.javaclawv1.runtime.tools.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

@Component
@ConditionalOnProperty(name = "javaclaw.scenario.file")
public class ToolMockRegistry {

    private static final Logger log = LoggerFactory.getLogger(ToolMockRegistry.class);

    private final ObjectMapper objectMapper;
    private volatile List<ScenarioConfigV2.ToolMock> currentMocks = Collections.emptyList();

    public ToolMockRegistry(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void setCurrentStepMocks(List<ScenarioConfigV2.ToolMock> mocks) {
        this.currentMocks = mocks != null ? mocks : Collections.emptyList();
        if (!this.currentMocks.isEmpty()) {
            log.info("[ToolMockRegistry] Loaded {} mock(s) for current step", this.currentMocks.size());
        }
    }

    public void clearMocks() {
        this.currentMocks = Collections.emptyList();
    }

    public Optional<ToolResult> tryMatch(String toolName, JsonNode input) {
        for (ScenarioConfigV2.ToolMock mock : currentMocks) {
            if (!toolName.equals(mock.tool())) continue;

            if (matchesInput(mock.match(), input)) {
                log.info("[ToolMockRegistry] Mock matched for tool '{}' (type={})",
                        toolName, mock.match() != null ? mock.match().type() : "any");
                return Optional.of(toToolResult(mock.result()));
            }
        }
        return Optional.empty();
    }

    boolean matchesInput(ScenarioConfigV2.ToolMockMatch match, JsonNode input) {
        if (match == null || match.type() == null || "any".equals(match.type())) {
            return true;
        }

        switch (match.type()) {
            case "containsKeys":
                if (match.keys() == null) return true;
                for (String key : match.keys()) {
                    if (!input.has(key)) return false;
                }
                return true;

            case "exact":
                if (match.json() == null) return true;
                try {
                    JsonNode expected = objectMapper.readTree(match.json());
                    return input.equals(expected);
                } catch (Exception e) {
                    log.warn("[ToolMockRegistry] Failed to parse exact match JSON: {}", match.json());
                    return false;
                }

            case "regex":
                if (match.pattern() == null) return true;
                return Pattern.compile(match.pattern(), Pattern.DOTALL)
                        .matcher(input.toString())
                        .find();

            default:
                log.warn("[ToolMockRegistry] Unknown match type: {}", match.type());
                return false;
        }
    }

    private ToolResult toToolResult(ScenarioConfigV2.ToolMockResult mockResult) {
        if (mockResult == null) {
            return ToolResult.success(objectMapper.createObjectNode().put("mock", true));
        }
        if (mockResult.success()) {
            JsonNode outputNode;
            if (mockResult.output() instanceof String s) {
                outputNode = objectMapper.createObjectNode().put("result", s);
            } else if (mockResult.output() != null) {
                outputNode = objectMapper.valueToTree(mockResult.output());
            } else {
                outputNode = objectMapper.createObjectNode().put("mock", true);
            }
            return ToolResult.success(outputNode);
        } else {
            return ToolResult.failure(mockResult.error() != null ? mockResult.error() : "Mock failure");
        }
    }
}
