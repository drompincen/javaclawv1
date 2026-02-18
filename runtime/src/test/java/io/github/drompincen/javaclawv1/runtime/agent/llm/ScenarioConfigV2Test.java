package io.github.drompincen.javaclawv1.runtime.agent.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static org.assertj.core.api.Assertions.assertThat;

class ScenarioConfigV2Test {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void deserializePmToolsV2Scenario() throws Exception {
        try (InputStream is = getClass().getResourceAsStream("/scenario-pm-tools-v2.json")) {
            ScenarioConfigV2 config = objectMapper.readValue(is, ScenarioConfigV2.class);

            assertThat(config.schemaVersion()).isEqualTo(2);
            assertThat(config.projectName()).isEqualTo("Sprint Tracker");
            assertThat(config.defaults()).isNotNull();
            assertThat(config.defaults().maxWaitMs()).isEqualTo(30000L);
            assertThat(config.defaults().requireCheckerPass()).isFalse();
            assertThat(config.steps()).hasSize(4);

            // First step is context — expects sessionStatus only
            ScenarioConfigV2.Step contextStep = config.steps().get(0);
            assertThat(contextStep.name()).isEqualTo("set-project-context");
            assertThat(contextStep.type()).isEqualTo("context");
            assertThat(contextStep.expects()).isNotNull();
            assertThat(contextStep.expects().sessionStatus()).isEqualTo("COMPLETED");

            // Second step has expects with events, mongo, messages
            ScenarioConfigV2.Step createTicket = config.steps().get(1);
            assertThat(createTicket.name()).isEqualTo("create-ticket");
            assertThat(createTicket.agentResponses()).hasSize(3);
            assertThat(createTicket.expects()).isNotNull();
            assertThat(createTicket.expects().sessionStatus()).isEqualTo("COMPLETED");
            assertThat(createTicket.expects().events().containsTypes())
                    .containsExactly("AGENT_DELEGATED", "TOOL_RESULT");
            assertThat(createTicket.expects().events().minCounts())
                    .containsEntry("TOOL_RESULT", 1);
            assertThat(createTicket.expects().mongo()).hasSize(1);
            assertThat(createTicket.expects().mongo().get(0).collection()).isEqualTo("tickets");
            assertThat(createTicket.expects().mongo().get(0).assertCondition().countGte()).isEqualTo(1);
            assertThat(createTicket.expects().messages().anyAssistantContains()).isEqualTo("ticket");
        }
    }

    @Test
    void deserializeMemoryV2Scenario() throws Exception {
        try (InputStream is = getClass().getResourceAsStream("/scenario-memory-v2.json")) {
            ScenarioConfigV2 config = objectMapper.readValue(is, ScenarioConfigV2.class);

            assertThat(config.schemaVersion()).isEqualTo(2);
            assertThat(config.steps()).hasSize(3);

            // Store step has exists assertion
            ScenarioConfigV2.Step storeStep = config.steps().get(0);
            assertThat(storeStep.expects().mongo()).hasSize(1);
            assertThat(storeStep.expects().mongo().get(0).filter()).containsEntry("key", "framework-version");
            assertThat(storeStep.expects().mongo().get(0).assertCondition().countGte()).isEqualTo(1);

            // Delete step expects count == 0
            ScenarioConfigV2.Step deleteStep = config.steps().get(2);
            assertThat(deleteStep.expects().mongo().get(0).assertCondition().countEq()).isEqualTo(0);
        }
    }

    @Test
    void deserializeFsToolsV2Scenario() throws Exception {
        try (InputStream is = getClass().getResourceAsStream("/scenario-fs-tools-v2.json")) {
            ScenarioConfigV2 config = objectMapper.readValue(is, ScenarioConfigV2.class);

            assertThat(config.schemaVersion()).isEqualTo(2);
            assertThat(config.projectName()).isEqualTo("JavaClaw v1");
            assertThat(config.steps()).hasSize(4);

            ScenarioConfigV2.Step listDir = config.steps().get(1);
            assertThat(listDir.expects().events().containsTypes())
                    .contains("TOOL_CALL_STARTED", "TOOL_RESULT");
        }
    }

    @Test
    void v1ScenarioHasNoSchemaVersion() throws Exception {
        try (InputStream is = getClass().getResourceAsStream("/scenario-pm-tools.json")) {
            ScenarioConfig config = objectMapper.readValue(is, ScenarioConfig.class);
            assertThat(config.projectName()).isEqualTo("Sprint Tracker");
            assertThat(config.steps()).hasSize(4);
        }
    }
}
