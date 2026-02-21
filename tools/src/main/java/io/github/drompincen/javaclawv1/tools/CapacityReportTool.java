package io.github.drompincen.javaclawv1.tools;

import io.github.drompincen.javaclawv1.persistence.document.ThingDocument;
import io.github.drompincen.javaclawv1.protocol.api.ThingCategory;
import io.github.drompincen.javaclawv1.protocol.api.ToolRiskProfile;
import io.github.drompincen.javaclawv1.runtime.thing.ThingService;
import io.github.drompincen.javaclawv1.runtime.tools.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.*;
import java.util.stream.Collectors;

public class CapacityReportTool implements Tool {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private ThingService thingService;

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

    public void setThingService(ThingService thingService) {
        this.thingService = thingService;
    }

    @Override
    public ToolResult execute(ToolContext ctx, JsonNode input, ToolStream stream) {
        if (thingService == null) return ToolResult.failure("ThingService not available");

        String projectId = input.path("projectId").asText(null);
        if (projectId == null || projectId.isBlank()) return ToolResult.failure("'projectId' is required");

        List<ThingDocument> resources = thingService.findByProjectAndCategory(projectId, ThingCategory.RESOURCE);
        List<ThingDocument> allAssignments = thingService.findByProjectAndCategory(projectId, ThingCategory.RESOURCE_ASSIGNMENT);

        // Group assignments by resourceId
        Map<String, List<ThingDocument>> assignmentsByResource = allAssignments.stream()
                .collect(Collectors.groupingBy(a -> (String) a.getPayload().get("resourceId")));

        // Build ticket lookup for names/priorities
        Map<String, ThingDocument> ticketMap = new HashMap<>();
        Set<String> ticketIds = allAssignments.stream()
                .map(a -> (String) a.getPayload().get("ticketId"))
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        for (String tid : ticketIds) {
            thingService.findById(tid, ThingCategory.TICKET).ifPresent(t -> ticketMap.put(tid, t));
        }

        ArrayNode resourcesArr = MAPPER.createArrayNode();
        int overloaded = 0, balanced = 0, underutilized = 0, idle = 0;

        for (ThingDocument r : resources) {
            Map<String, Object> p = r.getPayload();
            ObjectNode rNode = MAPPER.createObjectNode();
            rNode.put("resourceId", r.getId());
            rNode.put("name", (String) p.get("name"));
            rNode.put("role", p.get("role") != null ? p.get("role").toString() : "UNKNOWN");
            int totalCapacity = p.get("capacity") != null ? ((Number) p.get("capacity")).intValue() : 0;
            rNode.put("totalCapacity", totalCapacity);

            List<ThingDocument> assignments = assignmentsByResource.getOrDefault(r.getId(), List.of());
            double allocatedPercent = assignments.stream()
                    .mapToDouble(a -> {
                        Object alloc = a.getPayload().get("percentageAllocation");
                        return alloc != null ? ((Number) alloc).doubleValue() : 0;
                    }).sum();
            double availablePercent = Math.max(0, totalCapacity - allocatedPercent);

            rNode.put("allocatedPercent", allocatedPercent);
            rNode.put("availablePercent", availablePercent);

            String status;
            if (allocatedPercent == 0) { status = "IDLE"; idle++; }
            else if (allocatedPercent > 100) { status = "OVERLOADED"; overloaded++; }
            else if (allocatedPercent >= 70) { status = "BALANCED"; balanced++; }
            else { status = "UNDERUTILIZED"; underutilized++; }
            rNode.put("status", status);

            ArrayNode assignArr = rNode.putArray("assignments");
            for (ThingDocument a : assignments) {
                Map<String, Object> ap = a.getPayload();
                ObjectNode aNode = MAPPER.createObjectNode();
                String ticketId = (String) ap.get("ticketId");
                aNode.put("ticketId", ticketId);
                aNode.put("allocationPercent", ap.get("percentageAllocation") != null
                        ? ((Number) ap.get("percentageAllocation")).doubleValue() : 0);
                ThingDocument ticket = ticketMap.get(ticketId);
                if (ticket != null) {
                    Map<String, Object> tp = ticket.getPayload();
                    aNode.put("ticketTitle", (String) tp.get("title"));
                    aNode.put("ticketPriority", tp.get("priority") != null ? tp.get("priority").toString() : "UNKNOWN");
                }
                assignArr.add(aNode);
            }

            @SuppressWarnings("unchecked")
            List<String> skills = (List<String>) p.get("skills");
            ArrayNode skillsArr = rNode.putArray("skills");
            if (skills != null) skills.forEach(skillsArr::add);

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
