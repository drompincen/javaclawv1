package io.github.drompincen.javaclawv1.tools;

import io.github.drompincen.javaclawv1.persistence.document.ThreadDocument;
import io.github.drompincen.javaclawv1.persistence.repository.ThreadRepository;
import io.github.drompincen.javaclawv1.protocol.api.ToolRiskProfile;
import io.github.drompincen.javaclawv1.runtime.tools.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.Set;

public class RenameThreadTool implements Tool {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private ThreadRepository threadRepository;

    @Override public String name() { return "rename_thread"; }

    @Override public String description() {
        return "Rename a thread. Use the naming policy [PROJECT]-[TOPIC]-[DATE] for consistency.";
    }

    @Override public JsonNode inputSchema() {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        props.putObject("threadId").put("type", "string");
        props.putObject("newTitle").put("type", "string")
                .put("description", "New thread title");
        props.putObject("reason").put("type", "string")
                .put("description", "Reason for renaming");
        schema.putArray("required").add("threadId").add("newTitle");
        return schema;
    }

    @Override public JsonNode outputSchema() { return MAPPER.createObjectNode().put("type", "object"); }
    @Override public Set<ToolRiskProfile> riskProfiles() { return Set.of(ToolRiskProfile.AGENT_INTERNAL); }

    public void setThreadRepository(ThreadRepository threadRepository) {
        this.threadRepository = threadRepository;
    }

    @Override
    public ToolResult execute(ToolContext ctx, JsonNode input, ToolStream stream) {
        if (threadRepository == null) {
            return ToolResult.failure("Thread repository not available — ensure MongoDB is connected");
        }

        String threadId = input.path("threadId").asText(null);
        String newTitle = input.path("newTitle").asText(null);
        if (threadId == null || threadId.isBlank()) return ToolResult.failure("'threadId' is required");
        if (newTitle == null || newTitle.isBlank()) return ToolResult.failure("'newTitle' is required");

        ThreadDocument thread = threadRepository.findById(threadId).orElse(null);
        if (thread == null) {
            return ToolResult.failure("Thread not found: " + threadId);
        }

        String oldTitle = thread.getTitle();
        thread.setTitle(newTitle);
        thread.setUpdatedAt(Instant.now());
        threadRepository.save(thread);

        stream.progress(100, "Thread renamed: " + oldTitle + " → " + newTitle);

        ObjectNode result = MAPPER.createObjectNode();
        result.put("threadId", threadId);
        result.put("oldTitle", oldTitle != null ? oldTitle : "");
        result.put("newTitle", newTitle);
        return ToolResult.success(result);
    }
}
