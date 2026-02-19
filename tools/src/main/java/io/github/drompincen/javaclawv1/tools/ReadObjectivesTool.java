package io.github.drompincen.javaclawv1.tools;

import io.github.drompincen.javaclawv1.persistence.document.ObjectiveDocument;
import io.github.drompincen.javaclawv1.persistence.repository.ObjectiveRepository;
import io.github.drompincen.javaclawv1.protocol.api.ToolRiskProfile;
import io.github.drompincen.javaclawv1.runtime.tools.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.*;

public class ReadObjectivesTool implements Tool {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private ObjectiveRepository objectiveRepository;

    @Override public String name() { return "read_objectives"; }
    @Override public String description() { return "Read all objectives for a project."; }

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

    public void setObjectiveRepository(ObjectiveRepository objectiveRepository) {
        this.objectiveRepository = objectiveRepository;
    }

    @Override
    public ToolResult execute(ToolContext ctx, JsonNode input, ToolStream stream) {
        if (objectiveRepository == null) return ToolResult.failure("Objective repository not available");
        String projectId = input.path("projectId").asText(null);
        if (projectId == null || projectId.isBlank()) return ToolResult.failure("'projectId' is required");

        List<ObjectiveDocument> objectives = objectiveRepository.findByProjectId(projectId);
        ArrayNode arr = MAPPER.createArrayNode();
        for (ObjectiveDocument o : objectives) {
            ObjectNode n = MAPPER.createObjectNode();
            n.put("objectiveId", o.getObjectiveId());
            n.put("outcome", o.getOutcome());
            n.put("status", o.getStatus() != null ? o.getStatus().name() : "UNKNOWN");
            n.put("ticketCount", o.getTicketIds() != null ? o.getTicketIds().size() : 0);
            arr.add(n);
        }
        stream.progress(100, "Read " + objectives.size() + " objectives");
        ObjectNode result = MAPPER.createObjectNode();
        result.set("objectives", arr);
        result.put("count", objectives.size());
        return ToolResult.success(result);
    }
}
