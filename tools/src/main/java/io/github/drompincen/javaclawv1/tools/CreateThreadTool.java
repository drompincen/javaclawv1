package io.github.drompincen.javaclawv1.tools;

import io.github.drompincen.javaclawv1.persistence.document.MessageDocument;
import io.github.drompincen.javaclawv1.persistence.document.ThreadDocument;
import io.github.drompincen.javaclawv1.persistence.repository.MessageRepository;
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
    private MessageRepository messageRepository;

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
        props.putObject("content").put("type", "string")
                .put("description", "Organized markdown to seed as the first message in the thread");
        ObjectNode decisionsSchema = props.putObject("decisions");
        decisionsSchema.put("type", "array");
        decisionsSchema.put("description", "List of key decisions for this thread");
        decisionsSchema.putObject("items").put("type", "string");
        ObjectNode actionsSchema = props.putObject("actions");
        actionsSchema.put("type", "array");
        actionsSchema.put("description", "List of action items for this thread");
        ObjectNode actionItem = actionsSchema.putObject("items");
        actionItem.put("type", "object");
        ObjectNode actionProps = actionItem.putObject("properties");
        actionProps.putObject("text").put("type", "string");
        actionProps.putObject("assignee").put("type", "string");
        schema.putArray("required").add("projectId").add("title");
        return schema;
    }

    @Override public JsonNode outputSchema() { return MAPPER.createObjectNode().put("type", "object"); }
    @Override public Set<ToolRiskProfile> riskProfiles() { return Set.of(ToolRiskProfile.AGENT_INTERNAL); }

    public void setThreadRepository(ThreadRepository threadRepository) {
        this.threadRepository = threadRepository;
    }

    public void setMessageRepository(MessageRepository messageRepository) {
        this.messageRepository = messageRepository;
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

        // Dedup: if thread with same title exists, append content to it
        var existing = threadRepository.findByTitleIgnoreCaseAndProjectIdsContaining(title, projectId);
        if (existing.isPresent()) {
            ThreadDocument existingThread = existing.get();
            String newContent = input.path("content").asText(null);

            // Append new content to existing thread content
            if (newContent != null && !newContent.isBlank()) {
                String currentContent = existingThread.getContent();
                if (currentContent != null && !currentContent.isBlank()) {
                    existingThread.setContent(currentContent + "\n\n---\n\n" + newContent);
                } else {
                    existingThread.setContent(newContent);
                }

                // Also save as message for history
                if (messageRepository != null) {
                    long seq = messageRepository.countBySessionId(existingThread.getThreadId()) + 1;
                    MessageDocument msg = new MessageDocument();
                    msg.setMessageId(UUID.randomUUID().toString());
                    msg.setSessionId(existingThread.getThreadId());
                    msg.setSeq(seq);
                    msg.setRole("assistant");
                    msg.setAgentId("thread-agent");
                    msg.setContent(newContent);
                    msg.setTimestamp(Instant.now());
                    messageRepository.save(msg);
                }
            }

            // Merge new decisions into existing lists
            JsonNode decisionsNode = input.path("decisions");
            if (decisionsNode.isArray() && decisionsNode.size() > 0) {
                List<ThreadDocument.Decision> mergedDecisions = existingThread.getDecisions() != null
                        ? new ArrayList<>(existingThread.getDecisions()) : new ArrayList<>();
                for (JsonNode d : decisionsNode) {
                    ThreadDocument.Decision decision = new ThreadDocument.Decision();
                    decision.setText(d.asText());
                    decision.setDate(Instant.now());
                    mergedDecisions.add(decision);
                }
                existingThread.setDecisions(mergedDecisions);
            }

            // Merge new actions into existing lists
            JsonNode actionsNode = input.path("actions");
            if (actionsNode.isArray() && actionsNode.size() > 0) {
                List<ThreadDocument.ActionItem> mergedActions = existingThread.getActions() != null
                        ? new ArrayList<>(existingThread.getActions()) : new ArrayList<>();
                for (JsonNode a : actionsNode) {
                    ThreadDocument.ActionItem action = new ThreadDocument.ActionItem();
                    action.setText(a.path("text").asText(""));
                    String assignee = a.path("assignee").asText(null);
                    if (assignee != null && !assignee.isBlank()) action.setAssignee(assignee);
                    action.setStatus("OPEN");
                    mergedActions.add(action);
                }
                existingThread.setActions(mergedActions);
            }

            existingThread.setUpdatedAt(Instant.now());
            threadRepository.save(existingThread);

            ObjectNode result = MAPPER.createObjectNode();
            result.put("threadId", existingThread.getThreadId());
            result.put("title", title);
            result.put("projectId", projectId);
            result.put("status", "updated_existing");
            return ToolResult.success(result);
        }

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

        // Populate decisions from input
        JsonNode decisionsNode = input.path("decisions");
        if (decisionsNode.isArray()) {
            List<ThreadDocument.Decision> decisions = new ArrayList<>();
            for (JsonNode d : decisionsNode) {
                ThreadDocument.Decision decision = new ThreadDocument.Decision();
                decision.setText(d.asText());
                decision.setDate(Instant.now());
                decisions.add(decision);
            }
            doc.setDecisions(decisions);
        }

        // Populate actions from input
        JsonNode actionsNode = input.path("actions");
        if (actionsNode.isArray()) {
            List<ThreadDocument.ActionItem> actionItems = new ArrayList<>();
            for (JsonNode a : actionsNode) {
                ThreadDocument.ActionItem action = new ThreadDocument.ActionItem();
                action.setText(a.path("text").asText(""));
                String assignee = a.path("assignee").asText(null);
                if (assignee != null && !assignee.isBlank()) action.setAssignee(assignee);
                action.setStatus("OPEN");
                actionItems.add(action);
            }
            doc.setActions(actionItems);
        }

        // Set content directly on the thread document
        String content = input.path("content").asText(null);
        if (content != null && !content.isBlank()) {
            doc.setContent(content);
        }

        doc.setCreatedAt(Instant.now());
        doc.setUpdatedAt(Instant.now());

        threadRepository.save(doc);

        // Seed content as the first message in the thread
        if (content != null && !content.isBlank() && messageRepository != null) {
            MessageDocument msg = new MessageDocument();
            msg.setMessageId(UUID.randomUUID().toString());
            msg.setSessionId(doc.getThreadId());
            msg.setSeq(1);
            msg.setRole("assistant");
            msg.setAgentId("thread-agent");
            msg.setContent(content);
            msg.setTimestamp(Instant.now());
            messageRepository.save(msg);
        }

        stream.progress(100, "Thread created: " + title);

        ObjectNode result = MAPPER.createObjectNode();
        result.put("threadId", doc.getThreadId());
        result.put("title", title);
        result.put("projectId", projectId);
        result.put("seeded", content != null && !content.isBlank());
        return ToolResult.success(result);
    }
}
