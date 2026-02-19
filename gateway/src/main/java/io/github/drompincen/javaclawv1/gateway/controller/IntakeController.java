package io.github.drompincen.javaclawv1.gateway.controller;

import io.github.drompincen.javaclawv1.persistence.document.MessageDocument;
import io.github.drompincen.javaclawv1.persistence.document.SessionDocument;
import io.github.drompincen.javaclawv1.persistence.repository.MessageRepository;
import io.github.drompincen.javaclawv1.persistence.repository.SessionRepository;
import io.github.drompincen.javaclawv1.protocol.api.IntakePipelineRequest;
import io.github.drompincen.javaclawv1.protocol.api.IntakePipelineResponse;
import io.github.drompincen.javaclawv1.protocol.api.SessionStatus;
import io.github.drompincen.javaclawv1.runtime.agent.IntakePipelineService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/intake")
public class IntakeController {

    private final IntakePipelineService pipelineService;
    private final SessionRepository sessionRepository;
    private final MessageRepository messageRepository;

    public IntakeController(IntakePipelineService pipelineService,
                            SessionRepository sessionRepository,
                            MessageRepository messageRepository) {
        this.pipelineService = pipelineService;
        this.sessionRepository = sessionRepository;
        this.messageRepository = messageRepository;
    }

    @PostMapping("/pipeline")
    public ResponseEntity<IntakePipelineResponse> startPipeline(@RequestBody IntakePipelineRequest request) {
        String projectId = request.projectId();
        String content = request.content();

        if (projectId == null || projectId.isBlank()) {
            return ResponseEntity.badRequest().body(new IntakePipelineResponse(null, null, "ERROR: projectId required"));
        }
        if (content == null || content.isBlank()) {
            return ResponseEntity.badRequest().body(new IntakePipelineResponse(null, null, "ERROR: content required"));
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

        IntakePipelineResponse response = pipelineService.startPipeline(projectId, content, sourceSessionId);
        return ResponseEntity.accepted().body(response);
    }
}
