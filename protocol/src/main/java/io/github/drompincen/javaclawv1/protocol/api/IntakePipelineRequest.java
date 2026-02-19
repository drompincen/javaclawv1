package io.github.drompincen.javaclawv1.protocol.api;

public record IntakePipelineRequest(
        String projectId,
        String content,
        java.util.List<String> filePaths
) {}
