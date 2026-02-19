package io.github.drompincen.javaclawv1.tools;

import io.github.drompincen.javaclawv1.persistence.document.ChecklistDocument;
import io.github.drompincen.javaclawv1.persistence.repository.ChecklistRepository;
import io.github.drompincen.javaclawv1.protocol.api.ToolRiskProfile;
import io.github.drompincen.javaclawv1.runtime.tools.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.*;

public class ReadChecklistsTool implements Tool {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private ChecklistRepository checklistRepository;

    @Override public String name() { return "read_checklists"; }
    @Override public String description() { return "Read all checklists for a project."; }

    @Override public JsonNode inputSchema() {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        props.putObject("projectId").put("type", "string");
        schema.putArray("required").add("projectId");
        return schema;
    }

    @Override public JsonNode outputSchema() { return MAPPER.createObjectNode().put("type", "object"); }
    @Override public Set<ToolRiskProfile> riskProfiles() { return Set.of(ToolRiskProfile.READ_ONLY); }

    public void setChecklistRepository(ChecklistRepository checklistRepository) {
        this.checklistRepository = checklistRepository;
    }

    @Override
    public ToolResult execute(ToolContext ctx, JsonNode input, ToolStream stream) {
        if (checklistRepository == null) return ToolResult.failure("Checklist repository not available");
        String projectId = input.path("projectId").asText(null);
        if (projectId == null || projectId.isBlank()) return ToolResult.failure("'projectId' is required");

        List<ChecklistDocument> checklists = checklistRepository.findByProjectId(projectId);
        ArrayNode arr = MAPPER.createArrayNode();
        for (ChecklistDocument c : checklists) {
            ObjectNode n = MAPPER.createObjectNode();
            n.put("checklistId", c.getChecklistId());
            n.put("name", c.getName());
            n.put("status", c.getStatus() != null ? c.getStatus().name() : "UNKNOWN");
            n.put("itemCount", c.getItems() != null ? c.getItems().size() : 0);
            arr.add(n);
        }
        stream.progress(100, "Read " + checklists.size() + " checklists");
        ObjectNode result = MAPPER.createObjectNode();
        result.set("checklists", arr);
        result.put("count", checklists.size());
        return ToolResult.success(result);
    }
}
