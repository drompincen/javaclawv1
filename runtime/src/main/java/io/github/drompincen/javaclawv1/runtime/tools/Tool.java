package io.github.drompincen.javaclawv1.runtime.tools;

import io.github.drompincen.javaclawv1.protocol.api.ToolRiskProfile;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.Set;

public interface Tool {

    String name();

    String description();

    JsonNode inputSchema();

    JsonNode outputSchema();

    Set<ToolRiskProfile> riskProfiles();

    ToolResult execute(ToolContext ctx, JsonNode input, ToolStream stream);
}
