package io.github.drompincen.javaclawv1.runtime.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AgentStepResultTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void replyCreatesNonDoneResult() {
        AgentStepResult result = AgentStepResult.reply("Hello world");

        assertThat(result.response()).isEqualTo("Hello world");
        assertThat(result.done()).isFalse();
        assertThat(result.toolCallName()).isNull();
    }

    @Test
    void toolCallCreatesResultWithToolInfo() {
        ObjectNode input = mapper.createObjectNode().put("path", "/tmp/file.txt");
        AgentStepResult result = AgentStepResult.toolCall("read_file", input);

        assertThat(result.toolCallName()).isEqualTo("read_file");
        assertThat(result.toolCallInput()).isNotNull();
        assertThat(result.done()).isFalse();
    }

    @Test
    void stopCreatesDoneResult() {
        AgentStepResult result = AgentStepResult.stop("max_steps");

        assertThat(result.done()).isTrue();
        assertThat(result.stopReason()).isEqualTo("max_steps");
    }
}
