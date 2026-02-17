package io.github.drompincen.javaclawv1.gateway.controller;

import io.github.drompincen.javaclawv1.protocol.api.ToolDescriptor;
import io.github.drompincen.javaclawv1.protocol.api.ToolRiskProfile;
import io.github.drompincen.javaclawv1.runtime.tools.ToolRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ToolControllerTest {

    @Mock
    private ToolRegistry toolRegistry;

    private ToolController controller;

    @BeforeEach
    void setUp() {
        controller = new ToolController(toolRegistry);
    }

    @Test
    void listToolsReturnsDescriptors() {
        ToolDescriptor desc = new ToolDescriptor("read_file", "Read a file", null, null,
                Set.of(ToolRiskProfile.READ_ONLY));
        when(toolRegistry.descriptors()).thenReturn(List.of(desc));

        var result = controller.list();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).name()).isEqualTo("read_file");
    }

    @Test
    void describeToolReturns404WhenNotFound() {
        when(toolRegistry.get("nonexistent")).thenReturn(Optional.empty());

        ResponseEntity<ToolDescriptor> response = controller.describe("nonexistent");

        assertThat(response.getStatusCode().value()).isEqualTo(404);
    }
}
