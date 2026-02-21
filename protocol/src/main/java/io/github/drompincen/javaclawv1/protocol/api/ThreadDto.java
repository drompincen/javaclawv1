package io.github.drompincen.javaclawv1.protocol.api;

import java.time.Instant;
import java.util.List;

public record ThreadDto(
        String threadId,
        List<String> projectIds,
        String title,
        SessionStatus status,
        ModelConfig modelConfig,
        ToolPolicy toolPolicy,
        String currentCheckpointId,
        Instant createdAt,
        Instant updatedAt,
        String summary,
        String content,
        List<DecisionDto> decisions,
        List<ActionDto> actions,
        int evidenceCount,
        List<String> objectiveIds,
        String lifecycle,
        List<String> mergedFromThreadIds,
        String mergedIntoThreadId
) {
    public record DecisionDto(String text, String decidedBy) {}
    public record ActionDto(String text, String assignee, String status) {}
}
