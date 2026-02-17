package io.github.drompincen.javaclawv1.protocol.api;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

public record CreateSpecRequest(
        String title,
        List<String> tags,
        String source,
        JsonNode jsonBody
) {}
