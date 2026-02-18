package io.github.drompincen.javaclawv1.runtime.agent.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.drompincen.javaclawv1.runtime.tools.ToolResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class ToolMockRegistryTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private ToolMockRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new ToolMockRegistry(objectMapper);
    }

    @Test
    void matchAny_returnsSuccessResult() throws Exception {
        ScenarioConfigV2.ToolMock mock = new ScenarioConfigV2.ToolMock(
                "create_ticket",
                new ScenarioConfigV2.ToolMockMatch("any", null, null, null),
                new ScenarioConfigV2.ToolMockResult(true, "ticket created", null)
        );
        registry.setCurrentStepMocks(List.of(mock));

        JsonNode input = objectMapper.readTree("{\"title\": \"Test\"}");
        Optional<ToolResult> result = registry.tryMatch("create_ticket", input);

        assertThat(result).isPresent();
        assertThat(result.get().success()).isTrue();
    }

    @Test
    void matchAny_wrongTool_returnsEmpty() throws Exception {
        ScenarioConfigV2.ToolMock mock = new ScenarioConfigV2.ToolMock(
                "create_ticket",
                new ScenarioConfigV2.ToolMockMatch("any", null, null, null),
                new ScenarioConfigV2.ToolMockResult(true, "ticket created", null)
        );
        registry.setCurrentStepMocks(List.of(mock));

        JsonNode input = objectMapper.readTree("{\"title\": \"Test\"}");
        Optional<ToolResult> result = registry.tryMatch("create_idea", input);

        assertThat(result).isEmpty();
    }

    @Test
    void matchContainsKeys_allKeysPresent_matches() throws Exception {
        ScenarioConfigV2.ToolMock mock = new ScenarioConfigV2.ToolMock(
                "create_ticket",
                new ScenarioConfigV2.ToolMockMatch("containsKeys", List.of("title", "priority"), null, null),
                new ScenarioConfigV2.ToolMockResult(true, "ok", null)
        );
        registry.setCurrentStepMocks(List.of(mock));

        JsonNode input = objectMapper.readTree("{\"title\": \"Test\", \"priority\": \"HIGH\", \"extra\": 1}");
        Optional<ToolResult> result = registry.tryMatch("create_ticket", input);

        assertThat(result).isPresent();
        assertThat(result.get().success()).isTrue();
    }

    @Test
    void matchContainsKeys_missingKey_noMatch() throws Exception {
        ScenarioConfigV2.ToolMock mock = new ScenarioConfigV2.ToolMock(
                "create_ticket",
                new ScenarioConfigV2.ToolMockMatch("containsKeys", List.of("title", "priority"), null, null),
                new ScenarioConfigV2.ToolMockResult(true, "ok", null)
        );
        registry.setCurrentStepMocks(List.of(mock));

        JsonNode input = objectMapper.readTree("{\"title\": \"Test\"}");
        Optional<ToolResult> result = registry.tryMatch("create_ticket", input);

        assertThat(result).isEmpty();
    }

    @Test
    void matchExact_jsonEquals_matches() throws Exception {
        String json = "{\"title\":\"Test\",\"priority\":\"HIGH\"}";
        ScenarioConfigV2.ToolMock mock = new ScenarioConfigV2.ToolMock(
                "create_ticket",
                new ScenarioConfigV2.ToolMockMatch("exact", null, json, null),
                new ScenarioConfigV2.ToolMockResult(true, "exact match", null)
        );
        registry.setCurrentStepMocks(List.of(mock));

        JsonNode input = objectMapper.readTree(json);
        Optional<ToolResult> result = registry.tryMatch("create_ticket", input);

        assertThat(result).isPresent();
    }

    @Test
    void matchExact_jsonNotEqual_noMatch() throws Exception {
        ScenarioConfigV2.ToolMock mock = new ScenarioConfigV2.ToolMock(
                "create_ticket",
                new ScenarioConfigV2.ToolMockMatch("exact", null, "{\"title\":\"Other\"}", null),
                new ScenarioConfigV2.ToolMockResult(true, "exact match", null)
        );
        registry.setCurrentStepMocks(List.of(mock));

        JsonNode input = objectMapper.readTree("{\"title\":\"Test\"}");
        Optional<ToolResult> result = registry.tryMatch("create_ticket", input);

        assertThat(result).isEmpty();
    }

    @Test
    void matchRegex_patternFound_matches() throws Exception {
        ScenarioConfigV2.ToolMock mock = new ScenarioConfigV2.ToolMock(
                "memory",
                new ScenarioConfigV2.ToolMockMatch("regex", null, null, ".*operation.*store.*"),
                new ScenarioConfigV2.ToolMockResult(true, "stored", null)
        );
        registry.setCurrentStepMocks(List.of(mock));

        JsonNode input = objectMapper.readTree("{\"operation\":\"store\",\"key\":\"test\"}");
        Optional<ToolResult> result = registry.tryMatch("memory", input);

        assertThat(result).isPresent();
    }

    @Test
    void matchRegex_patternNotFound_noMatch() throws Exception {
        ScenarioConfigV2.ToolMock mock = new ScenarioConfigV2.ToolMock(
                "memory",
                new ScenarioConfigV2.ToolMockMatch("regex", null, null, ".*operation.*delete.*"),
                new ScenarioConfigV2.ToolMockResult(true, "deleted", null)
        );
        registry.setCurrentStepMocks(List.of(mock));

        JsonNode input = objectMapper.readTree("{\"operation\":\"store\",\"key\":\"test\"}");
        Optional<ToolResult> result = registry.tryMatch("memory", input);

        assertThat(result).isEmpty();
    }

    @Test
    void nullMatch_treatedAsAny() throws Exception {
        ScenarioConfigV2.ToolMock mock = new ScenarioConfigV2.ToolMock(
                "create_ticket",
                null,
                new ScenarioConfigV2.ToolMockResult(true, "default", null)
        );
        registry.setCurrentStepMocks(List.of(mock));

        JsonNode input = objectMapper.readTree("{\"anything\":true}");
        Optional<ToolResult> result = registry.tryMatch("create_ticket", input);

        assertThat(result).isPresent();
    }

    @Test
    void failureResult_returnsToolResultFailure() throws Exception {
        ScenarioConfigV2.ToolMock mock = new ScenarioConfigV2.ToolMock(
                "create_ticket",
                new ScenarioConfigV2.ToolMockMatch("any", null, null, null),
                new ScenarioConfigV2.ToolMockResult(false, null, "mock error")
        );
        registry.setCurrentStepMocks(List.of(mock));

        JsonNode input = objectMapper.readTree("{}");
        Optional<ToolResult> result = registry.tryMatch("create_ticket", input);

        assertThat(result).isPresent();
        assertThat(result.get().success()).isFalse();
        assertThat(result.get().error()).isEqualTo("mock error");
    }

    @Test
    void clearMocks_removesAllMocks() throws Exception {
        ScenarioConfigV2.ToolMock mock = new ScenarioConfigV2.ToolMock(
                "create_ticket",
                null,
                new ScenarioConfigV2.ToolMockResult(true, "ok", null)
        );
        registry.setCurrentStepMocks(List.of(mock));
        registry.clearMocks();

        JsonNode input = objectMapper.readTree("{}");
        Optional<ToolResult> result = registry.tryMatch("create_ticket", input);

        assertThat(result).isEmpty();
    }

    @Test
    void noMocksSet_returnsEmpty() throws Exception {
        JsonNode input = objectMapper.readTree("{\"title\":\"Test\"}");
        Optional<ToolResult> result = registry.tryMatch("create_ticket", input);

        assertThat(result).isEmpty();
    }
}
