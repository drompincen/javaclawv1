package io.github.drompincen.javaclawv1.gateway.controller;

import io.github.drompincen.javaclawv1.persistence.document.MessageDocument;
import io.github.drompincen.javaclawv1.persistence.document.SessionDocument;
import io.github.drompincen.javaclawv1.persistence.repository.MessageRepository;
import io.github.drompincen.javaclawv1.persistence.repository.SessionRepository;
import io.github.drompincen.javaclawv1.protocol.api.IntakePipelineRequest;
import io.github.drompincen.javaclawv1.protocol.api.IntakePipelineResponse;
import io.github.drompincen.javaclawv1.protocol.api.SessionStatus;
import io.github.drompincen.javaclawv1.protocol.api.ThingCategory;
import io.github.drompincen.javaclawv1.protocol.api.UploadStatus;
import io.github.drompincen.javaclawv1.runtime.agent.IntakePipelineService;
import io.github.drompincen.javaclawv1.runtime.thing.ThingService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.*;

@RestController
@RequestMapping("/api/intake")
public class IntakeController {

    private final IntakePipelineService pipelineService;
    private final SessionRepository sessionRepository;
    private final MessageRepository messageRepository;
    private final ThingService thingService;

    public IntakeController(IntakePipelineService pipelineService,
                            SessionRepository sessionRepository,
                            MessageRepository messageRepository,
                            ThingService thingService) {
        this.pipelineService = pipelineService;
        this.sessionRepository = sessionRepository;
        this.messageRepository = messageRepository;
        this.thingService = thingService;
    }

    @PostMapping("/pipeline")
    public ResponseEntity<IntakePipelineResponse> startPipeline(@RequestBody IntakePipelineRequest request) {
        String projectId = request.projectId();
        String content = request.content();

        if (projectId == null || projectId.isBlank()) {
            return ResponseEntity.badRequest().body(new IntakePipelineResponse(null, null, "ERROR: projectId required"));
        }
        boolean hasFiles = request.filePaths() != null && !request.filePaths().isEmpty();
        if ((content == null || content.isBlank()) && !hasFiles) {
            return ResponseEntity.badRequest().body(new IntakePipelineResponse(null, null, "ERROR: content or filePaths required"));
        }

        // Create source session for UI tracking
        String sourceSessionId = UUID.randomUUID().toString();
        SessionDocument sourceSession = new SessionDocument();
        sourceSession.setSessionId(sourceSessionId);
        sourceSession.setProjectId(projectId);
        sourceSession.setStatus(SessionStatus.RUNNING);
        sourceSession.setCreatedAt(Instant.now());
        sourceSession.setUpdatedAt(Instant.now());
        sourceSession.setMetadata(Map.of("type", "intake-pipeline"));
        sessionRepository.save(sourceSession);

        // Save the raw content as the first user message
        MessageDocument msg = new MessageDocument();
        msg.setMessageId(UUID.randomUUID().toString());
        msg.setSessionId(sourceSessionId);
        msg.setSeq(1);
        msg.setRole("user");
        msg.setContent(content);
        msg.setTimestamp(Instant.now());
        messageRepository.save(msg);

        List<String> filePaths = request.filePaths() != null ? request.filePaths() : List.of();
        IntakePipelineResponse response = pipelineService.startPipeline(projectId, content, sourceSessionId, filePaths);
        return ResponseEntity.accepted().body(response);
    }

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<List<UploadInfo>> uploadFiles(
            @RequestParam String projectId,
            @RequestParam("files") List<MultipartFile> files) throws IOException {

        if (projectId == null || projectId.isBlank()) {
            return ResponseEntity.badRequest().build();
        }

        Path uploadDir = Paths.get(System.getProperty("user.dir"), "uploads", projectId);
        Files.createDirectories(uploadDir);

        List<UploadInfo> results = new ArrayList<>();
        for (MultipartFile file : files) {
            String originalName = file.getOriginalFilename() != null ? file.getOriginalFilename() : "unnamed";
            String savedName = UUID.randomUUID() + "-" + originalName;
            Path dest = uploadDir.resolve(savedName);
            file.transferTo(dest.toFile());

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("source", "file_upload");
            payload.put("title", originalName);
            payload.put("contentType", file.getContentType());
            payload.put("binaryRef", dest.toAbsolutePath().toString());
            payload.put("status", UploadStatus.INBOX.name());

            var thing = thingService.createThing(projectId, ThingCategory.UPLOAD, payload);

            String detectedType = detectContentType(originalName);
            results.add(new UploadInfo(thing.getId(), originalName, dest.toAbsolutePath().toString(), detectedType));
        }

        return ResponseEntity.ok(results);
    }

    private String detectContentType(String fileName) {
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".xlsx") || lower.endsWith(".xls")) return "spreadsheet";
        if (lower.endsWith(".csv")) return "csv";
        if (lower.endsWith(".json")) return "json";
        if (lower.endsWith(".xml")) return "xml";
        if (lower.endsWith(".html") || lower.endsWith(".htm")) return "html";
        if (lower.endsWith(".txt") || lower.endsWith(".md")) return "text";
        return "unknown";
    }

    record UploadInfo(String uploadId, String fileName, String filePath, String contentType) {}
}
