package io.github.drompincen.javaclawv1.gateway.controller;

import io.github.drompincen.javaclawv1.persistence.document.MessageDocument;
import io.github.drompincen.javaclawv1.persistence.document.SessionDocument;
import io.github.drompincen.javaclawv1.persistence.repository.MessageRepository;
import io.github.drompincen.javaclawv1.persistence.repository.SessionRepository;
import io.github.drompincen.javaclawv1.protocol.api.*;
import io.github.drompincen.javaclawv1.protocol.event.EventType;
import io.github.drompincen.javaclawv1.runtime.agent.AgentLoop;
import io.github.drompincen.javaclawv1.runtime.agent.EventService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/sessions")
public class SessionController {

    private final SessionRepository sessionRepository;
    private final MessageRepository messageRepository;
    private final AgentLoop agentLoop;
    private final EventService eventService;

    public SessionController(SessionRepository sessionRepository,
                             MessageRepository messageRepository,
                             AgentLoop agentLoop,
                             EventService eventService) {
        this.sessionRepository = sessionRepository;
        this.messageRepository = messageRepository;
        this.agentLoop = agentLoop;
        this.eventService = eventService;
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody(required = false) CreateSessionRequest req) {
        if (req == null) req = new CreateSessionRequest(null, null, null, null);
        SessionDocument doc = new SessionDocument();
        doc.setSessionId(UUID.randomUUID().toString());
        doc.setProjectId(req.projectId());
        doc.setCreatedAt(Instant.now());
        doc.setUpdatedAt(Instant.now());
        doc.setStatus(SessionStatus.IDLE);
        doc.setModelConfig(req.modelConfig() != null ? req.modelConfig() : ModelConfig.defaults());
        doc.setToolPolicy(req.toolPolicy() != null ? req.toolPolicy() : ToolPolicy.allowAll());
        doc.setMetadata(req.metadata() != null ? req.metadata() : Map.of());
        sessionRepository.save(doc);
        return ResponseEntity.ok(toDto(doc));
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> get(@PathVariable String id) {
        return sessionRepository.findById(id)
                .map(d -> ResponseEntity.ok(toDto(d)))
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping
    public List<SessionDto> list() {
        return sessionRepository.findAllByOrderByUpdatedAtDesc().stream()
                .map(this::toDto).collect(Collectors.toList());
    }

    @PostMapping("/{id}/messages")
    public ResponseEntity<?> sendMessage(@PathVariable String id, @RequestBody SendMessageRequest req) {
        if (sessionRepository.findById(id).isEmpty()) return ResponseEntity.notFound().build();
        long seq = messageRepository.countBySessionId(id) + 1;
        MessageDocument msg = new MessageDocument();
        msg.setMessageId(UUID.randomUUID().toString());
        msg.setSessionId(id);
        msg.setSeq(seq);
        msg.setRole(req.role() != null ? req.role() : "user");
        msg.setContent(req.content());
        msg.setTimestamp(Instant.now());

        // Handle multimodal parts
        if (req.parts() != null && !req.parts().isEmpty()) {
            msg.setParts(req.parts().stream().map(p -> {
                var part = new MessageDocument.ContentPart();
                part.setType(p.type());
                part.setText(p.text());
                part.setMediaType(p.mediaType());
                part.setData(p.data());
                return part;
            }).toList());
        }

        messageRepository.save(msg);
        String displayContent = req.content() != null ? req.content() : "(multimodal message)";
        eventService.emit(id, EventType.USER_MESSAGE_RECEIVED,
                Map.of("content", displayContent, "role", msg.getRole()));
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/{id}/run")
    public ResponseEntity<?> run(@PathVariable String id) {
        if (sessionRepository.findById(id).isEmpty()) return ResponseEntity.notFound().build();
        agentLoop.startAsync(id);
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/{id}/pause")
    public ResponseEntity<?> pause(@PathVariable String id) {
        agentLoop.stop(id);
        sessionRepository.findById(id).ifPresent(s -> {
            s.setStatus(SessionStatus.PAUSED);
            s.setUpdatedAt(Instant.now());
            sessionRepository.save(s);
            eventService.emit(id, EventType.SESSION_STATUS_CHANGED, Map.of("status", "PAUSED"));
        });
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/{id}/resume")
    public ResponseEntity<?> resume(@PathVariable String id) {
        if (sessionRepository.findById(id).isEmpty()) return ResponseEntity.notFound().build();
        agentLoop.startAsync(id);
        return ResponseEntity.accepted().build();
    }

    private SessionDto toDto(SessionDocument doc) {
        return new SessionDto(doc.getSessionId(), doc.getThreadId(), doc.getProjectId(),
                doc.getCreatedAt(), doc.getUpdatedAt(),
                doc.getStatus(), doc.getModelConfig(), doc.getToolPolicy(),
                doc.getCurrentCheckpointId(), doc.getMetadata());
    }
}
