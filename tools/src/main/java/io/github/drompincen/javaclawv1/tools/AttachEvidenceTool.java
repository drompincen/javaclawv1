package io.github.drompincen.javaclawv1.tools;

import io.github.drompincen.javaclawv1.persistence.document.ThreadDocument;
import io.github.drompincen.javaclawv1.persistence.repository.ThreadRepository;
import io.github.drompincen.javaclawv1.protocol.api.ToolRiskProfile;
import io.github.drompincen.javaclawv1.runtime.tools.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Set;

public class AttachEvidenceTool implements Tool {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private ThreadRepository threadRepository;

    @Override public String name() { return "attach_evidence"; }

    @Override public String description() {
        return "Attach an evidence reference (file, URL, upload, design doc) to a thread for traceability.";
    }

    @Override public JsonNode inputSchema() {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        props.putObject("threadId").put("type", "string");
        props.putObject("evidenceType").put("type", "string")
                .put("description", "Type of evidence: file, url, upload, design");
        props.putObject("referenceId").put("type", "string")
                .put("description", "Reference identifier (path, URL, or upload ID)");
        props.putObject("label").put("type", "string")
                .put("description", "Human-readable label for the evidence");
        schema.putArray("required").add("threadId").add("evidenceType").add("referenceId");
        return schema;
    }

    @Override public JsonNode outputSchema() { return MAPPER.createObjectNode().put("type", "object"); }
    @Override public Set<ToolRiskProfile> riskProfiles() { return Set.of(ToolRiskProfile.AGENT_INTERNAL); }

    public void setThreadRepository(ThreadRepository threadRepository) {
        this.threadRepository = threadRepository;
    }

    @Override
    public ToolResult execute(ToolContext ctx, JsonNode input, ToolStream stream) {
        if (threadRepository == null) {
            return ToolResult.failure("Thread repository not available — ensure MongoDB is connected");
        }

        String threadId = input.path("threadId").asText(null);
        String evidenceType = input.path("evidenceType").asText(null);
        String referenceId = input.path("referenceId").asText(null);
        if (threadId == null || threadId.isBlank()) return ToolResult.failure("'threadId' is required");
        if (evidenceType == null || evidenceType.isBlank()) return ToolResult.failure("'evidenceType' is required");
        if (referenceId == null || referenceId.isBlank()) return ToolResult.failure("'referenceId' is required");

        ThreadDocument thread = threadRepository.findById(threadId).orElse(null);
        if (thread == null) {
            return ToolResult.failure("Thread not found: " + threadId);
        }

        if (thread.getEvidence() == null) {
            thread.setEvidence(new ArrayList<>());
        }

        String label = input.path("label").asText(evidenceType + ": " + referenceId);

        ThreadDocument.EvidenceRef ref = new ThreadDocument.EvidenceRef();
        ref.setUploadId(referenceId);
        ref.setSnippet(label);
        ref.setRelevance(1.0);
        thread.getEvidence().add(ref);
        thread.setUpdatedAt(Instant.now());
        threadRepository.save(thread);

        stream.progress(100, "Evidence attached to thread: " + label);

        ObjectNode result = MAPPER.createObjectNode();
        result.put("threadId", threadId);
        result.put("evidenceCount", thread.getEvidence().size());
        return ToolResult.success(result);
    }
}
