package io.github.drompincen.javaclawv1.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.drompincen.javaclawv1.persistence.document.MessageDocument;
import io.github.drompincen.javaclawv1.persistence.repository.MessageRepository;
import io.github.drompincen.javaclawv1.runtime.tools.ToolContext;
import io.github.drompincen.javaclawv1.runtime.tools.ToolResult;
import io.github.drompincen.javaclawv1.runtime.tools.ToolStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ReadThreadMessagesToolTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Mock private MessageRepository messageRepository;
    @Mock private ToolStream stream;

    private ReadThreadMessagesTool tool;
    private ToolContext ctx;

    @BeforeEach
    void setUp() {
        tool = new ReadThreadMessagesTool();
        tool.setMessageRepository(messageRepository);
        ctx = new ToolContext("session-1", Path.of("."), Map.of());
    }

    @Test
    void failsWithoutRepository() {
        ReadThreadMessagesTool unwired = new ReadThreadMessagesTool();
        ObjectNode input = MAPPER.createObjectNode().put("threadId", "t1");
        ToolResult result = unwired.execute(ctx, input, stream);
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("repository not available");
    }

    @Test
    void failsWithoutThreadId() {
        ObjectNode input = MAPPER.createObjectNode();
        ToolResult result = tool.execute(ctx, input, stream);
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("threadId");
    }

    @Test
    void readsMessagesWithDefaults() {
        MessageDocument msg1 = new MessageDocument();
        msg1.setRole("user");
        msg1.setContent("Hello");
        msg1.setSeq(1);

        MessageDocument msg2 = new MessageDocument();
        msg2.setRole("assistant");
        msg2.setContent("Hi there");
        msg2.setSeq(2);

        when(messageRepository.findBySessionIdOrderBySeqAsc("thread-1"))
                .thenReturn(List.of(msg1, msg2));

        ObjectNode input = MAPPER.createObjectNode().put("threadId", "thread-1");
        ToolResult result = tool.execute(ctx, input, stream);

        assertThat(result.success()).isTrue();
        JsonNode output = result.output();
        assertThat(output.get("total").asInt()).isEqualTo(2);
        assertThat(output.get("hasMore").asBoolean()).isFalse();
        assertThat(output.get("messages").size()).isEqualTo(2);
        assertThat(output.get("messages").get(0).get("role").asText()).isEqualTo("user");
        assertThat(output.get("messages").get(1).get("content").asText()).isEqualTo("Hi there");
    }

    @Test
    void respectsPagination() {
        List<MessageDocument> messages = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            MessageDocument msg = new MessageDocument();
            msg.setRole("user");
            msg.setContent("Message " + i);
            msg.setSeq(i);
            messages.add(msg);
        }

        when(messageRepository.findBySessionIdOrderBySeqAsc("t1")).thenReturn(messages);

        ObjectNode input = MAPPER.createObjectNode();
        input.put("threadId", "t1");
        input.put("limit", 2);
        input.put("offset", 1);

        ToolResult result = tool.execute(ctx, input, stream);

        assertThat(result.success()).isTrue();
        JsonNode output = result.output();
        assertThat(output.get("total").asInt()).isEqualTo(5);
        assertThat(output.get("hasMore").asBoolean()).isTrue();
        assertThat(output.get("messages").size()).isEqualTo(2);
        assertThat(output.get("messages").get(0).get("content").asText()).isEqualTo("Message 2");
    }
}
