package io.github.drompincen.javaclawv1.tools;

import io.github.drompincen.javaclawv1.persistence.document.ResourceDocument;
import io.github.drompincen.javaclawv1.persistence.repository.ResourceRepository;
import io.github.drompincen.javaclawv1.protocol.api.ToolRiskProfile;
import io.github.drompincen.javaclawv1.runtime.tools.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.*;

public class ReadResourcesTool implements Tool {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private ResourceRepository resourceRepository;

    @Override public String name() { return "read_resources"; }
    @Override public String description() { return "Read all resources for a project."; }

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

    public void setResourceRepository(ResourceRepository resourceRepository) {
        this.resourceRepository = resourceRepository;
    }

    @Override
    public ToolResult execute(ToolContext ctx, JsonNode input, ToolStream stream) {
        if (resourceRepository == null) return ToolResult.failure("Resource repository not available");
        String projectId = input.path("projectId").asText(null);
        if (projectId == null || projectId.isBlank()) return ToolResult.failure("'projectId' is required");

        List<ResourceDocument> resources = resourceRepository.findByProjectId(projectId);
        ArrayNode arr = MAPPER.createArrayNode();
        for (ResourceDocument r : resources) {
            ObjectNode n = MAPPER.createObjectNode();
            n.put("resourceId", r.getResourceId());
            n.put("name", r.getName());
            n.put("role", r.getRole() != null ? r.getRole().name() : "UNKNOWN");
            n.put("capacity", r.getCapacity());
            n.put("availability", r.getAvailability());
            ArrayNode skillsArr = n.putArray("skills");
            if (r.getSkills() != null) r.getSkills().forEach(skillsArr::add);
            arr.add(n);
        }
        stream.progress(100, "Read " + resources.size() + " resources");
        ObjectNode result = MAPPER.createObjectNode();
        result.set("resources", arr);
        result.put("count", resources.size());
        return ToolResult.success(result);
    }
}
