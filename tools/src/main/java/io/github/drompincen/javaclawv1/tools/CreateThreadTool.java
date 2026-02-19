package io.github.drompincen.javaclawv1.tools;

import io.github.drompincen.javaclawv1.persistence.document.ThreadDocument;
import io.github.drompincen.javaclawv1.persistence.repository.ThreadRepository;
import io.github.drompincen.javaclawv1.protocol.api.ThreadLifecycle;
import io.github.drompincen.javaclawv1.protocol.api.ToolRiskProfile;
import io.github.drompincen.javaclawv1.runtime.tools.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public class CreateThreadTool implements Tool {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private ThreadRepository threadRepository;

    @Override public String name() { return "create_thread"; }

    @Override public String description() {
        return "Create a new thread for a project. Threads organize conversations by topic and workstream.";
    }

    @Override public JsonNode inputSchema() {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        props.putObject("projectId").put("type", "string");
        props.putObject("title").put("type", "string")
                .put("description", "Thread title, ideally in [PROJECT]-[TOPIC]-[DATE] format");
        props.putObject("summary").put("type", "string")
                .put("description", "Optional thread summary");
        props.putObject("lifecycle").put("type", "string")
                .put("description", "DRAFT, ACTIVE, or CLOSED (default ACTIVE)");
        schema.putArray("required").add("projectId").add("title");
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

        String projectId = input.path("projectId").asText(null);
        String title = input.path("title").asText(null);
        if (projectId == null || projectId.isBlank()) return ToolResult.failure("'projectId' is required");
        if (title == null || title.isBlank()) return ToolResult.failure("'title' is required");

        String lifecycleStr = input.path("lifecycle").asText("ACTIVE");
        ThreadLifecycle lifecycle;
        try {
            lifecycle = ThreadLifecycle.valueOf(lifecycleStr.toUpperCase());
        } catch (IllegalArgumentException e) {
            lifecycle = ThreadLifecycle.ACTIVE;
        }

        ThreadDocument doc = new ThreadDocument();
        doc.setThreadId(UUID.randomUUID().toString());
        doc.setProjectIds(List.of(projectId));
        doc.setTitle(title);
        doc.setLifecycle(lifecycle);
        doc.setEvidence(new ArrayList<>());
        doc.setDecisions(new ArrayList<>());
        doc.setActions(new ArrayList<>());

        String summary = input.path("summary").asText(null);
        if (summary != null && !summary.isBlank()) {
            doc.setSummary(summary);
        }

        doc.setCreatedAt(Instant.now());
        doc.setUpdatedAt(Instant.now());

        threadRepository.save(doc);
        stream.progress(100, "Thread created: " + title);

        ObjectNode result = MAPPER.createObjectNode();
        result.put("threadId", doc.getThreadId());
        result.put("title", title);
        result.put("projectId", projectId);
        return ToolResult.success(result);
    }
}
