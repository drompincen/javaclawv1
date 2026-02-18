package io.github.drompincen.javaclawv1.protocol.api;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record UploadDto(
        String uploadId,
        String projectId,
        String source,
        String sourceUrl,
        String title,
        String content,
        String contentType,
        String binaryRef,
        List<String> tags,
        List<String> people,
        List<String> systems,
        Instant contentTimestamp,
        Instant inferredTimestamp,
        String threadId,
        Double confidence,
        UploadStatus status,
        Map<String, String> metadata,
        Instant createdAt,
        Instant updatedAt
) {}
