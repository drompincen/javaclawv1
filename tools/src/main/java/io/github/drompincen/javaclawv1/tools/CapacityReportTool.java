package io.github.drompincen.javaclawv1.tools;

import io.github.drompincen.javaclawv1.persistence.document.ResourceAssignmentDocument;
import io.github.drompincen.javaclawv1.persistence.document.ResourceDocument;
import io.github.drompincen.javaclawv1.persistence.document.TicketDocument;
import io.github.drompincen.javaclawv1.persistence.repository.ResourceAssignmentRepository;
import io.github.drompincen.javaclawv1.persistence.repository.ResourceRepository;
import io.github.drompincen.javaclawv1.persistence.repository.TicketRepository;
import io.github.drompincen.javaclawv1.protocol.api.ToolRiskProfile;
import io.github.drompincen.javaclawv1.runtime.tools.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.*;
import java.util.stream.Collectors;

public class CapacityReportTool implements Tool {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private ResourceRepository resourceRepository;
    private ResourceAssignmentRepository resourceAssignmentRepository;
    private TicketRepository ticketRepository;

    @Override public String name() { return "capacity_report"; }
    @Override public String description() { return "Generate a capacity report for all resources in a project."; }

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
    public void setResourceAssignmentRepository(ResourceAssignmentRepository resourceAssignmentRepository) {
        this.resourceAssignmentRepository = resourceAssignmentRepository;
    }
    public void setTicketRepository(TicketRepository ticketRepository) {
        this.ticketRepository = ticketRepository;
    }

    @Override
    public ToolResult execute(ToolContext ctx, JsonNode input, ToolStream stream) {
        if (resourceRepository == null) return ToolResult.failure("Resource repository not available");
        if (resourceAssignmentRepository == null) return ToolResult.failure("Assignment repository not available");

        String projectId = input.path("projectId").asText(null);
        if (projectId == null || projectId.isBlank()) return ToolResult.failure("'projectId' is required");

        List<ResourceDocument> resources = resourceRepository.findByProjectId(projectId);
        List<ResourceAssignmentDocument> allAssignments = resourceAssignmentRepository.findByProjectId(projectId);

        // Group assignments by resourceId
        Map<String, List<ResourceAssignmentDocument>> assignmentsByResource = allAssignments.stream()
                .collect(Collectors.groupingBy(ResourceAssignmentDocument::getResourceId));

        // Build ticket lookup for names/priorities
        Map<String, TicketDocument> ticketMap = new HashMap<>();
        if (ticketRepository != null) {
            Set<String> ticketIds = allAssignments.stream()
                    .map(ResourceAssignmentDocument::getTicketId)
                    .collect(Collectors.toSet());
            for (String tid : ticketIds) {
                ticketRepository.findById(tid).ifPresent(t -> ticketMap.put(tid, t));
            }
        }

        ArrayNode resourcesArr = MAPPER.createArrayNode();
        int overloaded = 0, balanced = 0, underutilized = 0, idle = 0;

        for (ResourceDocument r : resources) {
            ObjectNode rNode = MAPPER.createObjectNode();
            rNode.put("resourceId", r.getResourceId());
            rNode.put("name", r.getName());
            rNode.put("role", r.getRole() != null ? r.getRole().name() : "UNKNOWN");
            rNode.put("totalCapacity", r.getCapacity());

            List<ResourceAssignmentDocument> assignments = assignmentsByResource.getOrDefault(r.getResourceId(), List.of());
            double allocatedPercent = assignments.stream().mapToDouble(ResourceAssignmentDocument::getPercentageAllocation).sum();
            double availablePercent = Math.max(0, r.getCapacity() - allocatedPercent);

            rNode.put("allocatedPercent", allocatedPercent);
            rNode.put("availablePercent", availablePercent);

            // Determine status
            String status;
            if (allocatedPercent == 0) { status = "IDLE"; idle++; }
            else if (allocatedPercent > 100) { status = "OVERLOADED"; overloaded++; }
            else if (allocatedPercent >= 70) { status = "BALANCED"; balanced++; }
            else { status = "UNDERUTILIZED"; underutilized++; }
            rNode.put("status", status);

            // Assignments detail
            ArrayNode assignArr = rNode.putArray("assignments");
            for (ResourceAssignmentDocument a : assignments) {
                ObjectNode aNode = MAPPER.createObjectNode();
                aNode.put("ticketId", a.getTicketId());
                aNode.put("allocationPercent", a.getPercentageAllocation());
                TicketDocument ticket = ticketMap.get(a.getTicketId());
                if (ticket != null) {
                    aNode.put("ticketTitle", ticket.getTitle());
                    aNode.put("ticketPriority", ticket.getPriority() != null ? ticket.getPriority().name() : "UNKNOWN");
                }
                assignArr.add(aNode);
            }

            // Skills
            ArrayNode skillsArr = rNode.putArray("skills");
            if (r.getSkills() != null) r.getSkills().forEach(skillsArr::add);

            resourcesArr.add(rNode);
        }

        stream.progress(100, "Generated capacity report for " + resources.size() + " resources");

        ObjectNode result = MAPPER.createObjectNode();
        result.set("resources", resourcesArr);
        ObjectNode summary = result.putObject("summary");
        summary.put("total", resources.size());
        summary.put("overloaded", overloaded);
        summary.put("balanced", balanced);
        summary.put("underutilized", underutilized);
        summary.put("idle", idle);
        return ToolResult.success(result);
    }
}
