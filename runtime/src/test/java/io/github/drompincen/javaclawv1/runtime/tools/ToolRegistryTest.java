package io.github.drompincen.javaclawv1.runtime.tools;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.drompincen.javaclawv1.protocol.api.ToolRiskProfile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ToolRegistryTest {

    private ToolRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new ToolRegistry();
    }

    @Test
    void registerAndRetrieveTool() {
        Tool mockTool = new Tool() {
            @Override public String name() { return "test_tool"; }
            @Override public String description() { return "A test tool"; }
            @Override public JsonNode inputSchema() { return null; }
            @Override public JsonNode outputSchema() { return null; }
            @Override public Set<ToolRiskProfile> riskProfiles() { return Set.of(ToolRiskProfile.READ_ONLY); }
            @Override public ToolResult execute(ToolContext ctx, JsonNode input, ToolStream stream) {
                return ToolResult.success(null);
            }
        };

        registry.register(mockTool);

        assertThat(registry.get("test_tool")).isPresent();
        assertThat(registry.get("test_tool").get().name()).isEqualTo("test_tool");
        assertThat(registry.get("nonexistent")).isEmpty();
    }

    @Test
    void allReturnsRegisteredTools() {
        assertThat(registry.all()).isEmpty();

        Tool mockTool = new Tool() {
            @Override public String name() { return "tool1"; }
            @Override public String description() { return "desc"; }
            @Override public JsonNode inputSchema() { return null; }
            @Override public JsonNode outputSchema() { return null; }
            @Override public Set<ToolRiskProfile> riskProfiles() { return Set.of(); }
            @Override public ToolResult execute(ToolContext ctx, JsonNode input, ToolStream stream) { return null; }
        };
        registry.register(mockTool);

        assertThat(registry.all()).hasSize(1);
    }

    @Test
    void descriptorsReturnsDtoList() {
        Tool mockTool = new Tool() {
            @Override public String name() { return "desc_tool"; }
            @Override public String description() { return "desc tool description"; }
            @Override public JsonNode inputSchema() { return null; }
            @Override public JsonNode outputSchema() { return null; }
            @Override public Set<ToolRiskProfile> riskProfiles() { return Set.of(ToolRiskProfile.WRITE_FILES); }
            @Override public ToolResult execute(ToolContext ctx, JsonNode input, ToolStream stream) { return null; }
        };
        registry.register(mockTool);

        var descriptors = registry.descriptors();
        assertThat(descriptors).hasSize(1);
        assertThat(descriptors.get(0).name()).isEqualTo("desc_tool");
        assertThat(descriptors.get(0).riskProfiles()).contains(ToolRiskProfile.WRITE_FILES);
    }
}
