package io.github.drompincen.javaclawv1.tools;

import io.github.drompincen.javaclawv1.persistence.document.IdeaDocument;
import io.github.drompincen.javaclawv1.persistence.repository.IdeaRepository;
import io.github.drompincen.javaclawv1.protocol.api.IdeaDto;
import io.github.drompincen.javaclawv1.protocol.api.ToolRiskProfile;
import io.github.drompincen.javaclawv1.runtime.tools.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public class CreateIdeaTool implements Tool {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private IdeaRepository ideaRepository;

    @Override public String name() { return "create_idea"; }
    @Override public String description() { return "Create a new idea in the project"; }

    @Override public JsonNode inputSchema() {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        props.putObject("projectId").put("type", "string");
        props.putObject("title").put("type", "string");
        props.putObject("content").put("type", "string");
        schema.putArray("required").add("projectId").add("title");
        return schema;
    }

    @Override public JsonNode outputSchema() { return MAPPER.createObjectNode().put("type", "object"); }
    @Override public Set<ToolRiskProfile> riskProfiles() { return Set.of(ToolRiskProfile.AGENT_INTERNAL); }

    public void setIdeaRepository(IdeaRepository ideaRepository) {
        this.ideaRepository = ideaRepository;
    }

    @Override
    public ToolResult execute(ToolContext ctx, JsonNode input, ToolStream stream) {
        if (ideaRepository == null) {
            return ToolResult.failure("Idea repository not available — ensure MongoDB is connected");
        }

        String projectId = input.path("projectId").asText(null);
        String title = input.path("title").asText(null);
        if (projectId == null || projectId.isBlank()) return ToolResult.failure("'projectId' is required");
        if (title == null || title.isBlank()) return ToolResult.failure("'title' is required");

        // Dedup: skip if idea with same title already exists for this project
        List<IdeaDocument> existing = ideaRepository.findByProjectId(projectId);
        for (IdeaDocument doc : existing) {
            if (doc.getTitle() != null && doc.getTitle().equalsIgnoreCase(title)) {
                ObjectNode result = MAPPER.createObjectNode();
                result.put("ideaId", doc.getIdeaId());
                result.put("status", "already_exists");
                result.put("projectId", projectId);
                return ToolResult.success(result);
            }
        }

        IdeaDocument doc = new IdeaDocument();
        doc.setIdeaId(UUID.randomUUID().toString());
        doc.setProjectId(projectId);
        doc.setTitle(title);
        doc.setContent(input.path("content").asText(null));
        doc.setStatus(IdeaDto.IdeaStatus.NEW);
        doc.setCreatedAt(Instant.now());
        doc.setUpdatedAt(Instant.now());

        ideaRepository.save(doc);
        stream.progress(100, "Idea created: " + title);

        ObjectNode result = MAPPER.createObjectNode();
        result.put("ideaId", doc.getIdeaId());
        result.put("status", "created");
        result.put("projectId", projectId);
        return ToolResult.success(result);
    }
}
