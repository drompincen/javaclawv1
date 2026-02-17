package io.github.drompincen.javaclawv1.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.drompincen.javaclawv1.protocol.api.ToolRiskProfile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HumanSearchToolTest {

    private HumanSearchTool tool;

    @BeforeEach
    void setUp() {
        tool = new HumanSearchTool();
    }

    @Test
    void nameAndMetadata() {
        assertThat(tool.name()).isEqualTo("human_search");
        assertThat(tool.riskProfiles()).contains(ToolRiskProfile.BROWSER_CONTROL);
        assertThat(tool.description()).contains("search");
        assertThat(tool.inputSchema()).isNotNull();
    }

    @Test
    void inputSchemaRequiresQuery() {
        var schema = tool.inputSchema();
        assertThat(schema.has("required")).isTrue();
        assertThat(schema.get("required").get(0).asText()).isEqualTo("query");
    }

    @Test
    void inputSchemaHasExpectedProperties() {
        var props = tool.inputSchema().get("properties");
        assertThat(props.has("query")).isTrue();
    }
}
