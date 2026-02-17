package io.github.drompincen.javaclawv1.protocol.api;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Set;

public record ToolDescriptor(
        String name,
        String description,
        JsonNode inputSchema,
        JsonNode outputSchema,
        Set<ToolRiskProfile> riskProfiles
) {}
