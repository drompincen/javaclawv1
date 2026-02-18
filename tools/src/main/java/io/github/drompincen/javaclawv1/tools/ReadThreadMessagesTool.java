package io.github.drompincen.javaclawv1.tools;

import io.github.drompincen.javaclawv1.persistence.document.MessageDocument;
import io.github.drompincen.javaclawv1.persistence.repository.MessageRepository;
import io.github.drompincen.javaclawv1.protocol.api.ToolRiskProfile;
import io.github.drompincen.javaclawv1.runtime.tools.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Set;

public class ReadThreadMessagesTool implements Tool {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private MessageRepository messageRepository;

    @Override public String name() { return "read_thread_messages"; }

    @Override public String description() {
        return "Read messages from a thread or session. Returns messages in sequence order with role, content, and seq number. " +
               "Supports pagination via limit and offset parameters.";
    }

    @Override public JsonNode inputSchema() {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        props.putObject("threadId").put("type", "string")
                .put("description", "The thread or session ID to read messages from");
        props.putObject("limit").put("type", "integer")
                .put("description", "Maximum number of messages to return (default 200)");
        props.putObject("offset").put("type", "integer")
                .put("description", "Number of messages to skip from the start (default 0)");
        schema.putArray("required").add("threadId");
        return schema;
    }

    @Override public JsonNode outputSchema() { return MAPPER.createObjectNode().put("type", "object"); }
    @Override public Set<ToolRiskProfile> riskProfiles() { return Set.of(ToolRiskProfile.READ_ONLY); }

    public void setMessageRepository(MessageRepository messageRepository) {
        this.messageRepository = messageRepository;
    }

    @Override
    public ToolResult execute(ToolContext ctx, JsonNode input, ToolStream stream) {
        if (messageRepository == null) {
            return ToolResult.failure("Message repository not available — ensure MongoDB is connected");
        }

        String threadId = input.path("threadId").asText(null);
        if (threadId == null || threadId.isBlank()) {
            return ToolResult.failure("'threadId' is required");
        }

        int limit = input.path("limit").asInt(200);
        int offset = input.path("offset").asInt(0);

        List<MessageDocument> allMessages = messageRepository.findBySessionIdOrderBySeqAsc(threadId);
        int total = allMessages.size();

        List<MessageDocument> page = allMessages.stream()
                .skip(offset)
                .limit(limit)
                .toList();

        ArrayNode messagesArray = MAPPER.createArrayNode();
        for (MessageDocument msg : page) {
            ObjectNode item = messagesArray.addObject();
            item.put("role", msg.getRole());
            item.put("content", msg.getContent() != null ? msg.getContent() : "");
            item.put("seq", msg.getSeq());
        }

        stream.progress(100, "Read " + page.size() + " messages from thread " + threadId);

        ObjectNode result = MAPPER.createObjectNode();
        result.set("messages", messagesArray);
        result.put("total", total);
        result.put("hasMore", offset + limit < total);
        return ToolResult.success(result);
    }
}
