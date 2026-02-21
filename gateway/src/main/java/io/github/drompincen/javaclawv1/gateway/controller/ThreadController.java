package io.github.drompincen.javaclawv1.gateway.controller;

import io.github.drompincen.javaclawv1.persistence.document.MessageDocument;
import io.github.drompincen.javaclawv1.persistence.document.ThreadDocument;
import io.github.drompincen.javaclawv1.persistence.repository.MessageRepository;
import io.github.drompincen.javaclawv1.persistence.repository.ThreadRepository;
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
@RequestMapping("/api/projects/{projectId}/threads")
public class ThreadController {

    private final ThreadRepository threadRepository;
    private final MessageRepository messageRepository;
    private final AgentLoop agentLoop;
    private final EventService eventService;

    public ThreadController(ThreadRepository threadRepository,
                            MessageRepository messageRepository,
                            AgentLoop agentLoop,
                            EventService eventService) {
        this.threadRepository = threadRepository;
        this.messageRepository = messageRepository;
        this.agentLoop = agentLoop;
        this.eventService = eventService;
    }

    @PostMapping
    public ResponseEntity<ThreadDto> create(@PathVariable String projectId, @RequestBody Map<String, Object> body) {
        ThreadDocument doc = new ThreadDocument();
        doc.setThreadId(UUID.randomUUID().toString());
        doc.setProjectIds(List.of(projectId));
        doc.setTitle((String) body.get("title"));
        doc.setContent((String) body.get("content"));
        doc.setSummary((String) body.get("summary"));
        doc.setCreatedAt(Instant.now());
        doc.setUpdatedAt(Instant.now());
        threadRepository.save(doc);
        return ResponseEntity.status(201).body(toDto(doc));
    }

    @GetMapping
    public List<ThreadDto> list(@PathVariable String projectId) {
        return threadRepository.findByProjectIdsOrderByUpdatedAtDesc(projectId).stream()
                .map(this::toDto).collect(Collectors.toList());
    }

    @GetMapping("/{threadId}")
    public ResponseEntity<ThreadDto> get(@PathVariable String projectId, @PathVariable String threadId) {
        return threadRepository.findById(threadId)
                .filter(t -> t.getEffectiveProjectIds().contains(projectId))
                .map(d -> ResponseEntity.ok(toDto(d)))
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/{threadId}/messages")
    public ResponseEntity<?> sendMessage(@PathVariable String projectId, @PathVariable String threadId,
                                         @RequestBody SendMessageRequest req) {
        if (threadRepository.findById(threadId).isEmpty()) return ResponseEntity.notFound().build();

        // Use threadId as sessionId for message storage (reuses existing infrastructure)
        long seq = messageRepository.countBySessionId(threadId) + 1;
        MessageDocument msg = new MessageDocument();
        msg.setMessageId(UUID.randomUUID().toString());
        msg.setSessionId(threadId);
        msg.setSeq(seq);
        msg.setRole(req.role() != null ? req.role() : "user");
        msg.setContent(req.content());
        msg.setTimestamp(Instant.now());

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
        eventService.emit(threadId, EventType.USER_MESSAGE_RECEIVED,
                Map.of("content", displayContent, "role", msg.getRole()));
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/{threadId}/run")
    public ResponseEntity<?> run(@PathVariable String projectId, @PathVariable String threadId) {
        if (threadRepository.findById(threadId).isEmpty()) return ResponseEntity.notFound().build();
        agentLoop.startAsync(threadId);
        return ResponseEntity.accepted().build();
    }

    @DeleteMapping("/{threadId}")
    public ResponseEntity<Void> delete(@PathVariable String projectId, @PathVariable String threadId) {
        return threadRepository.findById(threadId)
                .filter(t -> t.getEffectiveProjectIds().contains(projectId))
                .map(t -> {
                    agentLoop.stop(threadId);
                    messageRepository.deleteBySessionId(threadId);
                    threadRepository.deleteById(threadId);
                    return ResponseEntity.noContent().<Void>build();
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/{threadId}/pause")
    public ResponseEntity<?> pause(@PathVariable String projectId, @PathVariable String threadId) {
        agentLoop.stop(threadId);
        threadRepository.findById(threadId).ifPresent(t -> {
            t.setStatus(SessionStatus.PAUSED);
            t.setUpdatedAt(Instant.now());
            threadRepository.save(t);
            eventService.emit(threadId, EventType.SESSION_STATUS_CHANGED, Map.of("status", "PAUSED"));
        });
        return ResponseEntity.accepted().build();
    }

    private ThreadDto toDto(ThreadDocument doc) {
        List<ThreadDto.DecisionDto> decisions = doc.getDecisions() != null
                ? doc.getDecisions().stream()
                    .map(d -> new ThreadDto.DecisionDto(d.getText(), d.getDecidedBy()))
                    .collect(Collectors.toList())
                : List.of();

        List<ThreadDto.ActionDto> actions = doc.getActions() != null
                ? doc.getActions().stream()
                    .map(a -> new ThreadDto.ActionDto(a.getText(), a.getAssignee(),
                            a.getStatus() != null ? a.getStatus() : "OPEN"))
                    .collect(Collectors.toList())
                : List.of();

        return new ThreadDto(doc.getThreadId(), doc.getEffectiveProjectIds(), doc.getTitle(),
                doc.getStatus(), doc.getModelConfig(), doc.getToolPolicy(),
                doc.getCurrentCheckpointId(), doc.getCreatedAt(), doc.getUpdatedAt(),
                doc.getSummary(), doc.getContent(), decisions, actions,
                doc.getEvidence() != null ? doc.getEvidence().size() : 0,
                doc.getObjectiveIds() != null ? doc.getObjectiveIds() : List.of(),
                doc.getLifecycle() != null ? doc.getLifecycle().name() : null,
                doc.getMergedFromThreadIds(),
                doc.getMergedIntoThreadId());
    }

    @SuppressWarnings("unchecked")
    @PostMapping("/merge")
    public ResponseEntity<?> merge(@PathVariable String projectId, @RequestBody Map<String, Object> body) {
        List<String> sourceThreadIds = (List<String>) body.get("sourceThreadIds");
        String targetTitle = (String) body.get("targetTitle");

        if (sourceThreadIds == null || sourceThreadIds.size() < 2) {
            return ResponseEntity.badRequest().body(Map.of("error", "Need at least 2 thread IDs to merge"));
        }

        // Validate all threads exist and belong to project
        List<ThreadDocument> sourceThreads = new java.util.ArrayList<>();
        for (String tid : sourceThreadIds) {
            var opt = threadRepository.findById(tid);
            if (opt.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "Thread not found: " + tid));
            }
            ThreadDocument t = opt.get();
            if (!t.getEffectiveProjectIds().contains(projectId)) {
                return ResponseEntity.badRequest().body(Map.of("error", "Thread " + tid + " not in project " + projectId));
            }
            sourceThreads.add(t);
        }

        // Use first thread as merge target
        ThreadDocument target = sourceThreads.get(0);
        if (targetTitle != null && !targetTitle.isBlank()) {
            target.setTitle(targetTitle);
        }

        // Collect all messages from target + sources into memory
        List<MessageDocument> allMsgs = new java.util.ArrayList<>(
                messageRepository.findBySessionIdOrderBySeqAsc(target.getThreadId()));

        // Merge content from source threads into target
        StringBuilder contentBuilder = new StringBuilder(target.getContent() != null ? target.getContent() : "");
        for (int i = 1; i < sourceThreads.size(); i++) {
            ThreadDocument source = sourceThreads.get(i);
            allMsgs.addAll(messageRepository.findBySessionIdOrderBySeqAsc(source.getThreadId()));

            // Append source content to target
            if (source.getContent() != null && !source.getContent().isBlank()) {
                if (contentBuilder.length() > 0) contentBuilder.append("\n\n---\n\n");
                contentBuilder.append(source.getContent());
            }

            // Delete source messages
            messageRepository.deleteBySessionId(source.getThreadId());

            // Mark source as merged
            source.setLifecycle(io.github.drompincen.javaclawv1.protocol.api.ThreadLifecycle.MERGED);
            source.setMergedIntoThreadId(target.getThreadId());
            source.setUpdatedAt(Instant.now());
            threadRepository.save(source);
        }

        // Delete target messages to avoid seq collisions
        messageRepository.deleteBySessionId(target.getThreadId());

        // Sort all collected messages by timestamp, re-assign seq, save
        allMsgs.sort(java.util.Comparator.comparing(m -> m.getTimestamp() != null ? m.getTimestamp() : Instant.EPOCH));
        for (int i = 0; i < allMsgs.size(); i++) {
            allMsgs.get(i).setSessionId(target.getThreadId());
            allMsgs.get(i).setSeq(i + 1);
            messageRepository.save(allMsgs.get(i));
        }

        // Update target with merge metadata + merged content
        target.setContent(contentBuilder.toString());
        target.setMergedFromThreadIds(sourceThreadIds.subList(1, sourceThreadIds.size()));
        target.setUpdatedAt(Instant.now());
        threadRepository.save(target);

        return ResponseEntity.ok(toDto(target));
    }
}
