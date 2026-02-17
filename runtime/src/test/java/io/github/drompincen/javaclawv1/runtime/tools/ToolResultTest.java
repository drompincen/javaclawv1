package io.github.drompincen.javaclawv1.runtime.tools;

import com.fasterxml.jackson.databind.node.TextNode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ToolResultTest {

    @Test
    void successCreatesSuccessfulResult() {
        ToolResult result = ToolResult.success(new TextNode("output data"));

        assertThat(result.success()).isTrue();
        assertThat(result.output().asText()).isEqualTo("output data");
        assertThat(result.error()).isNull();
    }

    @Test
    void failureCreatesFailedResult() {
        ToolResult result = ToolResult.failure("something went wrong");

        assertThat(result.success()).isFalse();
        assertThat(result.error()).isEqualTo("something went wrong");
        assertThat(result.output()).isNull();
    }
}
