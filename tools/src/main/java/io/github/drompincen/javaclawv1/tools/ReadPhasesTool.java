package io.github.drompincen.javaclawv1.tools;

import io.github.drompincen.javaclawv1.persistence.document.PhaseDocument;
import io.github.drompincen.javaclawv1.persistence.repository.PhaseRepository;
import io.github.drompincen.javaclawv1.protocol.api.ToolRiskProfile;
import io.github.drompincen.javaclawv1.runtime.tools.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.*;

public class ReadPhasesTool implements Tool {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private PhaseRepository phaseRepository;

    @Override public String name() { return "read_phases"; }
    @Override public String description() { return "Read all phases for a project, ordered by sortOrder."; }

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

    public void setPhaseRepository(PhaseRepository phaseRepository) {
        this.phaseRepository = phaseRepository;
    }

    @Override
    public ToolResult execute(ToolContext ctx, JsonNode input, ToolStream stream) {
        if (phaseRepository == null) return ToolResult.failure("Phase repository not available");
        String projectId = input.path("projectId").asText(null);
        if (projectId == null || projectId.isBlank()) return ToolResult.failure("'projectId' is required");

        List<PhaseDocument> phases = phaseRepository.findByProjectIdOrderBySortOrder(projectId);
        ArrayNode arr = MAPPER.createArrayNode();
        for (PhaseDocument p : phases) {
            ObjectNode n = MAPPER.createObjectNode();
            n.put("phaseId", p.getPhaseId());
            n.put("name", p.getName());
            n.put("status", p.getStatus() != null ? p.getStatus().name() : "UNKNOWN");
            n.put("sortOrder", p.getSortOrder());
            arr.add(n);
        }
        stream.progress(100, "Read " + phases.size() + " phases");
        ObjectNode result = MAPPER.createObjectNode();
        result.set("phases", arr);
        result.put("count", phases.size());
        return ToolResult.success(result);
    }
}
