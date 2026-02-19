package io.github.drompincen.javaclawv1.tools;

import io.github.drompincen.javaclawv1.persistence.document.DeltaPackDocument;
import io.github.drompincen.javaclawv1.persistence.repository.DeltaPackRepository;
import io.github.drompincen.javaclawv1.protocol.api.ToolRiskProfile;
import io.github.drompincen.javaclawv1.runtime.tools.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.*;

public class CreateDeltaPackTool implements Tool {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private DeltaPackRepository deltaPackRepository;

    @Override public String name() { return "create_delta_pack"; }

    @Override public String description() {
        return "Create a delta pack documenting all mismatches found during reconciliation. " +
               "Records deltas with type, severity, and suggested actions.";
    }

    @Override public JsonNode inputSchema() {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        props.putObject("projectId").put("type", "string");
        props.putObject("projectName").put("type", "string");
        ObjectNode deltas = props.putObject("deltas");
        deltas.put("type", "array");
        ObjectNode deltaSchema = deltas.putObject("items");
        deltaSchema.put("type", "object");
        ObjectNode dp = deltaSchema.putObject("properties");
        dp.putObject("deltaType").put("type", "string");
        dp.putObject("severity").put("type", "string");
        dp.putObject("title").put("type", "string");
        dp.putObject("description").put("type", "string");
        dp.putObject("suggestedAction").put("type", "string");
        schema.putArray("required").add("projectId").add("deltas");
        return schema;
    }

    @Override public JsonNode outputSchema() { return MAPPER.createObjectNode().put("type", "object"); }
    @Override public Set<ToolRiskProfile> riskProfiles() { return Set.of(ToolRiskProfile.AGENT_INTERNAL); }

    public void setDeltaPackRepository(DeltaPackRepository deltaPackRepository) {
        this.deltaPackRepository = deltaPackRepository;
    }

    @Override
    public ToolResult execute(ToolContext ctx, JsonNode input, ToolStream stream) {
        if (deltaPackRepository == null) return ToolResult.failure("DeltaPack repository not available");

        String projectId = input.path("projectId").asText(null);
        if (projectId == null || projectId.isBlank()) return ToolResult.failure("'projectId' is required");

        JsonNode deltasNode = input.get("deltas");
        if (deltasNode == null || !deltasNode.isArray()) return ToolResult.failure("'deltas' must be an array");

        List<DeltaPackDocument.Delta> deltas = new ArrayList<>();
        Map<String, Integer> bySeverity = new LinkedHashMap<>();
        Map<String, Integer> byType = new LinkedHashMap<>();

        for (JsonNode dn : deltasNode) {
            DeltaPackDocument.Delta d = new DeltaPackDocument.Delta();
            d.setDeltaType(dn.path("deltaType").asText("UNKNOWN"));
            d.setSeverity(dn.path("severity").asText("MEDIUM"));
            d.setTitle(dn.path("title").asText(""));
            d.setDescription(dn.path("description").asText(""));
            d.setSourceA(dn.path("sourceA").asText(null));
            d.setSourceB(dn.path("sourceB").asText(null));
            d.setFieldName(dn.path("fieldName").asText(null));
            d.setValueA(dn.path("valueA").asText(null));
            d.setValueB(dn.path("valueB").asText(null));
            d.setSuggestedAction(dn.path("suggestedAction").asText(null));
            d.setAutoResolvable(dn.path("autoResolvable").asBoolean(false));
            deltas.add(d);

            bySeverity.merge(d.getSeverity(), 1, Integer::sum);
            byType.merge(d.getDeltaType(), 1, Integer::sum);
        }

        DeltaPackDocument doc = new DeltaPackDocument();
        doc.setDeltaPackId(UUID.randomUUID().toString());
        doc.setProjectId(projectId);
        doc.setProjectName(input.path("projectName").asText(null));
        doc.setDeltas(deltas);
        doc.setStatus("FINAL");

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("totalDeltas", deltas.size());
        summary.put("bySeverity", bySeverity);
        summary.put("byType", byType);
        doc.setSummary(summary);

        doc.setCreatedAt(Instant.now());
        doc.setUpdatedAt(Instant.now());

        deltaPackRepository.save(doc);
        stream.progress(100, "Delta pack created: " + deltas.size() + " deltas");

        ObjectNode result = MAPPER.createObjectNode();
        result.put("deltaPackId", doc.getDeltaPackId());
        result.put("totalDeltas", deltas.size());
        return ToolResult.success(result);
    }
}
