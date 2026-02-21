package io.github.drompincen.javaclawv1.tools;

import io.github.drompincen.javaclawv1.persistence.document.ThingDocument;
import io.github.drompincen.javaclawv1.protocol.api.ThingCategory;
import io.github.drompincen.javaclawv1.protocol.api.ToolRiskProfile;
import io.github.drompincen.javaclawv1.runtime.thing.ThingService;
import io.github.drompincen.javaclawv1.runtime.tools.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.*;

public class GeneratePlanArtifactTool implements Tool {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private ThingService thingService;

    @Override public String name() { return "generate_plan_artifact"; }

    @Override public String description() {
        return "Generate a structured plan document (markdown) summarizing all phases, milestones, " +
               "and timeline for a project.";
    }

    @Override public JsonNode inputSchema() {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        props.putObject("projectId").put("type", "string");
        props.putObject("includeTimeline").put("type", "boolean").put("description", "Include timeline table (default true)");
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
        if (thingService == null) {
            return ToolResult.failure("ThingService not available");
        }

        String projectId = input.path("projectId").asText(null);
        if (projectId == null || projectId.isBlank()) return ToolResult.failure("'projectId' is required");

        boolean includeTimeline = input.path("includeTimeline").asBoolean(true);

        List<ThingDocument> phases = thingService.findByProjectAndCategorySorted(
                projectId, ThingCategory.PHASE, "payload.sortOrder", true);
        List<ThingDocument> milestones = thingService.findByProjectAndCategorySorted(
                projectId, ThingCategory.MILESTONE, "payload.targetDate", true);

        StringBuilder md = new StringBuilder();
        md.append("# Project Plan\n\n");

        // Phases section
        md.append("## Phases\n\n");
        for (ThingDocument p : phases) {
            Map<String, Object> pp = p.getPayload();
            int sortOrder = pp.get("sortOrder") != null ? ((Number) pp.get("sortOrder")).intValue() : 0;
            md.append("### ").append(sortOrder).append(". ").append(safe(pp.get("name")));
            md.append(" [").append(pp.get("status") != null ? pp.get("status") : "UNKNOWN").append("]\n\n");
            if (pp.get("description") != null) md.append(pp.get("description")).append("\n\n");
            @SuppressWarnings("unchecked")
            List<String> entryCriteria = (List<String>) pp.get("entryCriteria");
            if (entryCriteria != null && !entryCriteria.isEmpty()) {
                md.append("**Entry Criteria:**\n");
                entryCriteria.forEach(c -> md.append("- ").append(c).append("\n"));
                md.append("\n");
            }
            @SuppressWarnings("unchecked")
            List<String> exitCriteria = (List<String>) pp.get("exitCriteria");
            if (exitCriteria != null && !exitCriteria.isEmpty()) {
                md.append("**Exit Criteria:**\n");
                exitCriteria.forEach(c -> md.append("- ").append(c).append("\n"));
                md.append("\n");
            }
        }

        // Milestones section
        if (!milestones.isEmpty()) {
            md.append("## Milestones\n\n");
            if (includeTimeline) {
                md.append("| Milestone | Target Date | Status | Owner |\n");
                md.append("|-----------|------------|--------|-------|\n");
                for (ThingDocument m : milestones) {
                    Map<String, Object> mp = m.getPayload();
                    md.append("| ").append(safe(mp.get("name")));
                    String td = mp.get("targetDate") != null ? mp.get("targetDate").toString() : "TBD";
                    md.append(" | ").append(td.length() >= 10 ? td.substring(0, 10) : td);
                    md.append(" | ").append(mp.get("status") != null ? mp.get("status") : "UNKNOWN");
                    md.append(" | ").append(mp.get("owner") != null ? mp.get("owner") : "-");
                    md.append(" |\n");
                }
                md.append("\n");
            } else {
                for (ThingDocument m : milestones) {
                    Map<String, Object> mp = m.getPayload();
                    md.append("- **").append(safe(mp.get("name"))).append("** — ");
                    md.append(mp.get("status") != null ? mp.get("status") : "UNKNOWN");
                    String td = mp.get("targetDate") != null ? mp.get("targetDate").toString() : "TBD";
                    md.append(", target: ").append(td.length() >= 10 ? td.substring(0, 10) : td);
                    md.append("\n");
                }
            }
        }

        stream.progress(100, "Plan artifact generated: " + phases.size() + " phases, " + milestones.size() + " milestones");

        ObjectNode result = MAPPER.createObjectNode();
        result.put("markdown", md.toString());
        result.put("phaseCount", phases.size());
        result.put("milestoneCount", milestones.size());
        return ToolResult.success(result);
    }

    private static String safe(Object val) {
        return val != null ? val.toString() : "";
    }
}
