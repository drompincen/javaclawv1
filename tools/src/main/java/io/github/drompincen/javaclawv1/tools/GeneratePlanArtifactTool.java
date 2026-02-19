package io.github.drompincen.javaclawv1.tools;

import io.github.drompincen.javaclawv1.persistence.document.MilestoneDocument;
import io.github.drompincen.javaclawv1.persistence.document.PhaseDocument;
import io.github.drompincen.javaclawv1.persistence.repository.MilestoneRepository;
import io.github.drompincen.javaclawv1.persistence.repository.PhaseRepository;
import io.github.drompincen.javaclawv1.protocol.api.ToolRiskProfile;
import io.github.drompincen.javaclawv1.runtime.tools.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.*;

public class GeneratePlanArtifactTool implements Tool {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private PhaseRepository phaseRepository;
    private MilestoneRepository milestoneRepository;

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

    public void setPhaseRepository(PhaseRepository phaseRepository) {
        this.phaseRepository = phaseRepository;
    }

    public void setMilestoneRepository(MilestoneRepository milestoneRepository) {
        this.milestoneRepository = milestoneRepository;
    }

    @Override
    public ToolResult execute(ToolContext ctx, JsonNode input, ToolStream stream) {
        if (phaseRepository == null || milestoneRepository == null) {
            return ToolResult.failure("Repositories not available");
        }

        String projectId = input.path("projectId").asText(null);
        if (projectId == null || projectId.isBlank()) return ToolResult.failure("'projectId' is required");

        boolean includeTimeline = input.path("includeTimeline").asBoolean(true);

        List<PhaseDocument> phases = phaseRepository.findByProjectIdOrderBySortOrder(projectId);
        List<MilestoneDocument> milestones = milestoneRepository.findByProjectIdOrderByTargetDateAsc(projectId);

        StringBuilder md = new StringBuilder();
        md.append("# Project Plan\n\n");

        // Phases section
        md.append("## Phases\n\n");
        for (PhaseDocument p : phases) {
            md.append("### ").append(p.getSortOrder()).append(". ").append(p.getName());
            md.append(" [").append(p.getStatus() != null ? p.getStatus().name() : "UNKNOWN").append("]\n\n");
            if (p.getDescription() != null) md.append(p.getDescription()).append("\n\n");
            if (p.getEntryCriteria() != null && !p.getEntryCriteria().isEmpty()) {
                md.append("**Entry Criteria:**\n");
                p.getEntryCriteria().forEach(c -> md.append("- ").append(c).append("\n"));
                md.append("\n");
            }
            if (p.getExitCriteria() != null && !p.getExitCriteria().isEmpty()) {
                md.append("**Exit Criteria:**\n");
                p.getExitCriteria().forEach(c -> md.append("- ").append(c).append("\n"));
                md.append("\n");
            }
        }

        // Milestones section
        if (!milestones.isEmpty()) {
            md.append("## Milestones\n\n");
            if (includeTimeline) {
                md.append("| Milestone | Target Date | Status | Owner |\n");
                md.append("|-----------|------------|--------|-------|\n");
                for (MilestoneDocument m : milestones) {
                    md.append("| ").append(m.getName());
                    md.append(" | ").append(m.getTargetDate() != null ? m.getTargetDate().toString().substring(0, 10) : "TBD");
                    md.append(" | ").append(m.getStatus() != null ? m.getStatus().name() : "UNKNOWN");
                    md.append(" | ").append(m.getOwner() != null ? m.getOwner() : "-");
                    md.append(" |\n");
                }
                md.append("\n");
            } else {
                for (MilestoneDocument m : milestones) {
                    md.append("- **").append(m.getName()).append("** — ");
                    md.append(m.getStatus() != null ? m.getStatus().name() : "UNKNOWN");
                    md.append(", target: ").append(m.getTargetDate() != null ? m.getTargetDate().toString().substring(0, 10) : "TBD");
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
}
