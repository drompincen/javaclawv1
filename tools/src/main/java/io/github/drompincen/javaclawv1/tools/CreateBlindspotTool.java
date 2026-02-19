package io.github.drompincen.javaclawv1.tools;

import io.github.drompincen.javaclawv1.persistence.document.BlindspotDocument;
import io.github.drompincen.javaclawv1.persistence.repository.BlindspotRepository;
import io.github.drompincen.javaclawv1.protocol.api.BlindspotCategory;
import io.github.drompincen.javaclawv1.protocol.api.BlindspotSeverity;
import io.github.drompincen.javaclawv1.protocol.api.BlindspotStatus;
import io.github.drompincen.javaclawv1.protocol.api.ToolRiskProfile;
import io.github.drompincen.javaclawv1.runtime.tools.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.*;

public class CreateBlindspotTool implements Tool {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private BlindspotRepository blindspotRepository;

    @Override public String name() { return "create_blindspot"; }

    @Override public String description() {
        return "Create a blindspot — a gap, risk, or issue discovered during reconciliation. " +
               "Categories: ORPHANED_TICKET, UNCOVERED_OBJECTIVE, UNASSIGNED_WORK, etc.";
    }

    @Override public JsonNode inputSchema() {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        props.putObject("projectId").put("type", "string");
        props.putObject("projectName").put("type", "string");
        props.putObject("title").put("type", "string");
        props.putObject("description").put("type", "string");
        props.putObject("category").put("type", "string")
                .put("description", "ORPHANED_TICKET, UNCOVERED_OBJECTIVE, EMPTY_PHASE, UNASSIGNED_WORK, MISSING_OWNER, DEPENDENCY_RISK, CAPACITY_GAP, SCOPE_OVERLAP, MISSING_TEST_SIGNAL, STALE_ARTIFACT");
        props.putObject("severity").put("type", "string")
                .put("description", "LOW, MEDIUM, HIGH, CRITICAL");
        props.putObject("owner").put("type", "string");
        props.putObject("deltaPackId").put("type", "string");
        schema.putArray("required").add("projectId").add("title").add("category").add("severity");
        return schema;
    }

    @Override public JsonNode outputSchema() { return MAPPER.createObjectNode().put("type", "object"); }
    @Override public Set<ToolRiskProfile> riskProfiles() { return Set.of(ToolRiskProfile.AGENT_INTERNAL); }

    public void setBlindspotRepository(BlindspotRepository blindspotRepository) {
        this.blindspotRepository = blindspotRepository;
    }

    @Override
    public ToolResult execute(ToolContext ctx, JsonNode input, ToolStream stream) {
        if (blindspotRepository == null) return ToolResult.failure("Blindspot repository not available");

        String projectId = input.path("projectId").asText(null);
        String title = input.path("title").asText(null);
        if (projectId == null || projectId.isBlank()) return ToolResult.failure("'projectId' is required");
        if (title == null || title.isBlank()) return ToolResult.failure("'title' is required");

        BlindspotCategory category;
        try {
            category = BlindspotCategory.valueOf(input.path("category").asText("").toUpperCase());
        } catch (IllegalArgumentException e) {
            return ToolResult.failure("Invalid category");
        }

        BlindspotSeverity severity;
        try {
            severity = BlindspotSeverity.valueOf(input.path("severity").asText("MEDIUM").toUpperCase());
        } catch (IllegalArgumentException e) {
            severity = BlindspotSeverity.MEDIUM;
        }

        BlindspotDocument doc = new BlindspotDocument();
        doc.setBlindspotId(UUID.randomUUID().toString());
        doc.setProjectId(projectId);
        doc.setProjectName(input.path("projectName").asText(null));
        doc.setTitle(title);
        doc.setDescription(input.path("description").asText(null));
        doc.setCategory(category);
        doc.setSeverity(severity);
        doc.setStatus(BlindspotStatus.OPEN);
        doc.setOwner(input.path("owner").asText(null));
        doc.setDeltaPackId(input.path("deltaPackId").asText(null));
        doc.setCreatedAt(Instant.now());
        doc.setUpdatedAt(Instant.now());

        blindspotRepository.save(doc);
        stream.progress(100, "Blindspot created: " + title);

        ObjectNode result = MAPPER.createObjectNode();
        result.put("blindspotId", doc.getBlindspotId());
        result.put("category", category.name());
        result.put("severity", severity.name());
        return ToolResult.success(result);
    }
}
