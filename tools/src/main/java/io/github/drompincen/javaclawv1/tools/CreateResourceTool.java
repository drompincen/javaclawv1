package io.github.drompincen.javaclawv1.tools;

import io.github.drompincen.javaclawv1.persistence.document.ResourceDocument;
import io.github.drompincen.javaclawv1.persistence.repository.ResourceRepository;
import io.github.drompincen.javaclawv1.protocol.api.ResourceDto;
import io.github.drompincen.javaclawv1.protocol.api.ToolRiskProfile;
import io.github.drompincen.javaclawv1.runtime.tools.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public class CreateResourceTool implements Tool {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private ResourceRepository resourceRepository;

    @Override public String name() { return "create_resource"; }

    @Override public String description() {
        return "Create a new team member resource for a project with role, skills, capacity, and availability.";
    }

    @Override public JsonNode inputSchema() {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        props.putObject("projectId").put("type", "string");
        props.putObject("name").put("type", "string").put("description", "Team member name");
        props.putObject("role").put("type", "string")
                .put("description", "ENGINEER, DESIGNER, PM, QA");
        props.putObject("skills").put("type", "array")
                .putObject("items").put("type", "string");
        props.putObject("capacity").put("type", "integer").put("description", "Capacity points (default 100)");
        props.putObject("availability").put("type", "number").put("description", "Availability fraction 0.0-1.0 (default 1.0)");
        props.putObject("email").put("type", "string").put("description", "Email address");
        schema.putArray("required").add("projectId").add("name");
        return schema;
    }

    @Override public JsonNode outputSchema() { return MAPPER.createObjectNode().put("type", "object"); }
    @Override public Set<ToolRiskProfile> riskProfiles() { return Set.of(ToolRiskProfile.AGENT_INTERNAL); }

    public void setResourceRepository(ResourceRepository resourceRepository) {
        this.resourceRepository = resourceRepository;
    }

    @Override
    public ToolResult execute(ToolContext ctx, JsonNode input, ToolStream stream) {
        if (resourceRepository == null) {
            return ToolResult.failure("Resource repository not available — ensure MongoDB is connected");
        }

        String projectId = input.path("projectId").asText(null);
        String name = input.path("name").asText(null);
        if (projectId == null || projectId.isBlank()) return ToolResult.failure("'projectId' is required");
        if (name == null || name.isBlank()) return ToolResult.failure("'name' is required");

        ResourceDocument doc = new ResourceDocument();
        doc.setResourceId(UUID.randomUUID().toString());
        doc.setProjectId(projectId);
        doc.setName(name);

        String roleStr = input.path("role").asText(null);
        if (roleStr != null && !roleStr.isBlank()) {
            try {
                doc.setRole(ResourceDto.ResourceRole.valueOf(roleStr.toUpperCase()));
            } catch (IllegalArgumentException e) {
                doc.setRole(ResourceDto.ResourceRole.ENGINEER);
            }
        } else {
            doc.setRole(ResourceDto.ResourceRole.ENGINEER);
        }

        JsonNode skillsNode = input.path("skills");
        if (skillsNode.isArray()) {
            List<String> skills = new ArrayList<>();
            for (JsonNode s : skillsNode) {
                skills.add(s.asText());
            }
            doc.setSkills(skills);
        }

        int capacity = input.path("capacity").asInt(100);
        doc.setCapacity(capacity);

        double availability = input.path("availability").asDouble(1.0);
        doc.setAvailability(availability);

        String email = input.path("email").asText(null);
        if (email != null && !email.isBlank()) {
            doc.setEmail(email);
        }

        resourceRepository.save(doc);

        stream.progress(100, "Resource created: " + name);

        ObjectNode result = MAPPER.createObjectNode();
        result.put("resourceId", doc.getResourceId());
        result.put("name", name);
        result.put("projectId", projectId);
        return ToolResult.success(result);
    }
}
