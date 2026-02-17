package io.github.drompincen.javaclawv1.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.drompincen.javaclawv1.protocol.api.ToolRiskProfile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JBangExecToolTest {

    private JBangExecTool tool;

    @BeforeEach
    void setUp() {
        tool = new JBangExecTool();
    }

    @Test
    void nameAndMetadata() {
        assertThat(tool.name()).isEqualTo("jbang_exec");
        assertThat(tool.riskProfiles()).containsExactly(ToolRiskProfile.EXEC_SHELL);
        assertThat(tool.description()).contains("JBang");
        assertThat(tool.inputSchema()).isNotNull();
    }

    @Test
    void inputSchemaRequiresCode() {
        var schema = tool.inputSchema();
        assertThat(schema.has("required")).isTrue();
        assertThat(schema.get("required").get(0).asText()).isEqualTo("code");
    }

    @Test
    void inputSchemaHasCodeAndTimeoutProperties() {
        var props = tool.inputSchema().get("properties");
        assertThat(props.has("code")).isTrue();
        assertThat(props.has("timeout_seconds")).isTrue();
    }
}
