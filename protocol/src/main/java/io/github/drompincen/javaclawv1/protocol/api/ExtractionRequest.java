package io.github.drompincen.javaclawv1.protocol.api;

import java.util.List;
import java.util.Set;

public record ExtractionRequest(
        String projectId,
        List<String> threadIds,
        Set<ExtractionType> types,
        boolean dryRun
) {}
