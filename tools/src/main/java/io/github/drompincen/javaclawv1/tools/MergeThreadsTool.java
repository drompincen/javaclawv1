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
import java.util.*;

public class MergeThreadsTool implements Tool {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private ThreadRepository threadRepository;
    private MessageRepository messageRepository;

    @Override public String name() { return "merge_threads"; }

    @Override public String description() {
        return "Merge two or more threads into one. Messages are interleaved by timestamp. " +
               "Source threads are marked MERGED with a pointer to the merged thread.";
    }

    @Override public JsonNode inputSchema() {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        ObjectNode ids = props.putObject("sourceThreadIds");
        ids.put("type", "array");
        ids.putObject("items").put("type", "string");
        ids.put("description", "Thread IDs to merge (minimum 2)");
        props.putObject("targetTitle").put("type", "string")
                .put("description", "Title for the merged thread (optional)");
        props.putObject("reason").put("type", "string")
                .put("description", "Reason for merging");
        schema.putArray("required").add("sourceThreadIds");
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
        if (threadRepository == null || messageRepository == null) {
            return ToolResult.failure("Repositories not available — ensure MongoDB is connected");
        }

        JsonNode idsNode = input.get("sourceThreadIds");
        if (idsNode == null || !idsNode.isArray() || idsNode.size() < 2) {
            return ToolResult.failure("'sourceThreadIds' must be an array with at least 2 thread IDs");
        }

        List<String> sourceIds = new ArrayList<>();
        for (JsonNode id : idsNode) {
            sourceIds.add(id.asText());
        }

        // Verify all threads exist
        List<ThreadDocument> sourceThreads = new ArrayList<>();
        for (String id : sourceIds) {
            ThreadDocument t = threadRepository.findById(id).orElse(null);
            if (t == null) {
                return ToolResult.failure("Thread not found: " + id);
            }
            sourceThreads.add(t);
        }

        // Use first thread as the merge target
        ThreadDocument target = sourceThreads.get(0);
        String targetTitle = input.path("targetTitle").asText(null);
        if (targetTitle != null && !targetTitle.isBlank()) {
            target.setTitle(targetTitle);
        }

        // Collect all messages from source threads, re-parent to target
        int totalMessages = 0;
        for (int i = 1; i < sourceThreads.size(); i++) {
            ThreadDocument source = sourceThreads.get(i);
            List<MessageDocument> msgs = messageRepository.findBySessionIdOrderBySeqAsc(source.getThreadId());
            for (MessageDocument msg : msgs) {
                msg.setSessionId(target.getThreadId());
                messageRepository.save(msg);
            }
            totalMessages += msgs.size();

            // Mark source as merged
            source.setLifecycle(ThreadLifecycle.MERGED);
            source.setMergedIntoThreadId(target.getThreadId());
            source.setUpdatedAt(Instant.now());
            threadRepository.save(source);
        }

        // Re-sequence all messages in the merged thread by timestamp
        List<MessageDocument> allMsgs = messageRepository.findBySessionIdOrderBySeqAsc(target.getThreadId());
        allMsgs.sort(Comparator.comparing(m -> m.getTimestamp() != null ? m.getTimestamp() : Instant.EPOCH));
        for (int i = 0; i < allMsgs.size(); i++) {
            allMsgs.get(i).setSeq(i + 1);
            messageRepository.save(allMsgs.get(i));
        }
        totalMessages += messageRepository.findBySessionIdOrderBySeqAsc(target.getThreadId()).size() - totalMessages;

        // Update target with merge metadata
        target.setMergedFromThreadIds(sourceIds.subList(1, sourceIds.size()));
        target.setUpdatedAt(Instant.now());
        threadRepository.save(target);

        stream.progress(100, "Merged " + sourceIds.size() + " threads into " + target.getThreadId());

        ObjectNode result = MAPPER.createObjectNode();
        result.put("mergedThreadId", target.getThreadId());
        result.put("sourceCount", sourceIds.size());
        result.put("totalMessages", allMsgs.size());
        return ToolResult.success(result);
    }
}
