package io.github.drompincen.javaclawv1.tools;

import io.github.drompincen.javaclawv1.persistence.document.MemoryDocument;
import io.github.drompincen.javaclawv1.persistence.repository.MemoryRepository;
import io.github.drompincen.javaclawv1.protocol.api.ToolRiskProfile;
import io.github.drompincen.javaclawv1.runtime.tools.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.*;

public class MemoryTool implements Tool {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private MemoryRepository memoryRepository;

    @Override public String name() { return "memory"; }

    @Override public String description() {
        return "Store and recall memories across sessions. Use 'store' to save knowledge (facts, patterns, preferences) " +
               "and 'recall' to retrieve it later. Memories persist in MongoDB and are shared across all agents. " +
               "Scopes: GLOBAL (all projects), PROJECT (specific project), SESSION (specific session), THREAD (specific thread).";
    }

    @Override public JsonNode inputSchema() {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");

        props.putObject("operation").put("type", "string")
                .put("description", "Operation: 'store' to save memory, 'recall' to retrieve, 'delete' to remove")
                .putArray("enum").add("store").add("recall").add("delete");

        props.putObject("scope").put("type", "string")
                .put("description", "Memory scope: GLOBAL, PROJECT, or SESSION")
                .putArray("enum").add("GLOBAL").add("PROJECT").add("SESSION").add("THREAD");

        props.putObject("key").put("type", "string")
                .put("description", "Short label for grouping (e.g., 'build-system', 'team-preferences'). Used for upsert on store.");

        props.putObject("content").put("type", "string")
                .put("description", "The knowledge/fact to store (for 'store' operation)");

        props.putObject("query").put("type", "string")
                .put("description", "Search query for 'recall' — searches content via regex (case-insensitive)");

        ObjectNode tagsNode = props.putObject("tags");
        tagsNode.put("type", "array");
        tagsNode.putObject("items").put("type", "string");
        tagsNode.put("description", "Tags for categorization (store) or filtering (recall)");

        schema.putArray("required").add("operation");
        return schema;
    }

    @Override public JsonNode outputSchema() { return MAPPER.createObjectNode().put("type", "object"); }
    @Override public Set<ToolRiskProfile> riskProfiles() { return Set.of(ToolRiskProfile.WRITE_FILES); }

    public void setMemoryRepository(MemoryRepository memoryRepository) {
        this.memoryRepository = memoryRepository;
    }

    @Override
    public ToolResult execute(ToolContext ctx, JsonNode input, ToolStream stream) {
        if (memoryRepository == null) {
            return ToolResult.failure("Memory repository not available — ensure MongoDB is connected");
        }

        String operation = input.path("operation").asText("recall");
        String scopeStr = input.path("scope").asText("GLOBAL");
        MemoryDocument.MemoryScope scope;
        try {
            scope = MemoryDocument.MemoryScope.valueOf(scopeStr.toUpperCase());
        } catch (IllegalArgumentException e) {
            return ToolResult.failure("Invalid scope: " + scopeStr + ". Use GLOBAL, PROJECT, SESSION, or THREAD.");
        }

        return switch (operation) {
            case "store" -> handleStore(ctx, input, scope, stream);
            case "recall" -> handleRecall(ctx, input, scope, stream);
            case "delete" -> handleDelete(ctx, input, scope);
            default -> ToolResult.failure("Unknown operation: " + operation + ". Use 'store', 'recall', or 'delete'.");
        };
    }

    private ToolResult handleStore(ToolContext ctx, JsonNode input, MemoryDocument.MemoryScope scope, ToolStream stream) {
        String key = input.path("key").asText(null);
        String content = input.path("content").asText(null);
        if (key == null || key.isBlank()) return ToolResult.failure("'key' is required for store operation");
        if (content == null || content.isBlank()) return ToolResult.failure("'content' is required for store operation");

        List<String> tags = new ArrayList<>();
        if (input.has("tags") && input.get("tags").isArray()) {
            input.get("tags").forEach(t -> tags.add(t.asText()));
        }

        // Upsert: find existing by scope+key, update or create
        Optional<MemoryDocument> existing = findByKey(scope, ctx.sessionId(), key);

        MemoryDocument doc;
        if (existing.isPresent()) {
            doc = existing.get();
            doc.setContent(content);
            doc.setTags(tags.isEmpty() ? doc.getTags() : tags);
            doc.setUpdatedAt(Instant.now());
        } else {
            doc = new MemoryDocument();
            doc.setMemoryId(UUID.randomUUID().toString());
            doc.setScope(scope);
            doc.setKey(key);
            doc.setContent(content);
            doc.setTags(tags);
            doc.setSessionId(ctx.sessionId());
            doc.setCreatedBy("agent");
            doc.setCreatedAt(Instant.now());
            doc.setUpdatedAt(Instant.now());
        }

        memoryRepository.save(doc);
        stream.progress(100, "Memory stored: " + key);

        ObjectNode result = MAPPER.createObjectNode();
        result.put("stored", true);
        result.put("memoryId", doc.getMemoryId());
        result.put("key", key);
        result.put("scope", scope.name());
        result.put("updated", existing.isPresent());
        return ToolResult.success(result);
    }

    private ToolResult handleRecall(ToolContext ctx, JsonNode input, MemoryDocument.MemoryScope scope, ToolStream stream) {
        String key = input.path("key").asText(null);
        String query = input.path("query").asText(null);

        List<MemoryDocument> results;

        if (key != null && !key.isBlank()) {
            // Exact key lookup
            Optional<MemoryDocument> found = findByKey(scope, ctx.sessionId(), key);
            results = found.map(List::of).orElse(List.of());
        } else if (query != null && !query.isBlank()) {
            // Content search
            results = memoryRepository.searchContent(query);
            // Filter by scope
            results = results.stream().filter(m -> m.getScope() == scope || scope == MemoryDocument.MemoryScope.GLOBAL).toList();
        } else {
            // Return all memories for scope
            results = switch (scope) {
                case GLOBAL -> memoryRepository.findByScope(MemoryDocument.MemoryScope.GLOBAL);
                case PROJECT -> memoryRepository.findByScopeAndProjectId(scope, null);
                case SESSION -> memoryRepository.findByScopeAndSessionId(scope, ctx.sessionId());
                case THREAD -> memoryRepository.findByScopeAndThreadId(scope, null);
            };
        }

        stream.progress(100, "Found " + results.size() + " memories");

        ArrayNode arr = MAPPER.createArrayNode();
        for (MemoryDocument m : results) {
            ObjectNode item = arr.addObject();
            item.put("memoryId", m.getMemoryId());
            item.put("scope", m.getScope().name());
            item.put("key", m.getKey());
            item.put("content", m.getContent());
            if (m.getTags() != null) {
                ArrayNode tagsArr = item.putArray("tags");
                m.getTags().forEach(tagsArr::add);
            }
            item.put("createdBy", m.getCreatedBy());
            item.put("updatedAt", m.getUpdatedAt() != null ? m.getUpdatedAt().toString() : null);
        }

        ObjectNode result = MAPPER.createObjectNode();
        result.put("count", results.size());
        result.set("memories", arr);
        return ToolResult.success(result);
    }

    private ToolResult handleDelete(ToolContext ctx, JsonNode input, MemoryDocument.MemoryScope scope) {
        String key = input.path("key").asText(null);
        if (key == null || key.isBlank()) return ToolResult.failure("'key' is required for delete operation");

        Optional<MemoryDocument> found = findByKey(scope, ctx.sessionId(), key);
        if (found.isEmpty()) {
            return ToolResult.failure("No memory found with key '" + key + "' in scope " + scope.name());
        }

        memoryRepository.deleteById(found.get().getMemoryId());
        ObjectNode result = MAPPER.createObjectNode();
        result.put("deleted", true);
        result.put("key", key);
        return ToolResult.success(result);
    }

    private Optional<MemoryDocument> findByKey(MemoryDocument.MemoryScope scope, String sessionId, String key) {
        return switch (scope) {
            case GLOBAL -> memoryRepository.findByScopeAndKey(scope, key);
            case PROJECT -> memoryRepository.findByScopeAndProjectIdAndKey(scope, null, key);
            case SESSION -> memoryRepository.findByScopeAndSessionIdAndKey(scope, sessionId, key);
            case THREAD -> memoryRepository.findByScopeAndThreadIdAndKey(scope, null, key);
        };
    }
}
