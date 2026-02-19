package io.github.drompincen.javaclawv1.protocol.api;

import java.util.List;

public record ResourceDto(
        String resourceId,
        String projectId,
        String name,
        String email,
        ResourceRole role,
        List<String> skills,
        int capacity,
        double availability
) {
    public enum ResourceRole {
        ENGINEER, DESIGNER, PM, QA
    }
}
