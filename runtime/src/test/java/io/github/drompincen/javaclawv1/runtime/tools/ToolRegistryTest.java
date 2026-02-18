package io.github.drompincen.javaclawv1.runtime.tools;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.drompincen.javaclawv1.protocol.api.ToolRiskProfile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.context.ApplicationContext;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ToolRegistryTest {

    private ToolRegistry registry;
    private ApplicationContext mockContext;

    @BeforeEach
    void setUp() {
        mockContext = mock(ApplicationContext.class);
        // Default: no beans available
        when(mockContext.getBean(any(Class.class))).thenThrow(new NoSuchBeanDefinitionException("none"));
        registry = new ToolRegistry(mockContext);
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

    @Test
    void injectDependenciesWiresSetterWhenBeanAvailable() {
        Object injectedValue = new Object();
        ToolWithSetter tool = new ToolWithSetter();

        // Use doReturn to avoid triggering the default stub
        doReturn(injectedValue).when(mockContext).getBean(Object.class);

        assertThat(tool.injectedObject).isNull();

        try {
            var method = ToolRegistry.class.getDeclaredMethod("injectDependencies", Tool.class);
            method.setAccessible(true);
            method.invoke(registry, tool);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        assertThat(tool.injectedObject).isSameAs(injectedValue);
    }

    @Test
    void injectDependenciesSkipsWhenNoBeanAvailable() {
        ToolWithSetter tool = new ToolWithSetter();

        // mockContext already throws NoSuchBeanDefinitionException
        try {
            var method = ToolRegistry.class.getDeclaredMethod("injectDependencies", Tool.class);
            method.setAccessible(true);
            method.invoke(registry, tool);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        assertThat(tool.injectedObject).isNull();
    }

    /** Test tool with a setter method for dependency injection */
    static class ToolWithSetter implements Tool {
        Object injectedObject;

        public void setInjectedObject(Object obj) { this.injectedObject = obj; }

        @Override public String name() { return "setter_tool"; }
        @Override public String description() { return "test"; }
        @Override public JsonNode inputSchema() { return null; }
        @Override public JsonNode outputSchema() { return null; }
        @Override public Set<ToolRiskProfile> riskProfiles() { return Set.of(); }
        @Override public ToolResult execute(ToolContext ctx, JsonNode input, ToolStream stream) { return null; }
    }
}
