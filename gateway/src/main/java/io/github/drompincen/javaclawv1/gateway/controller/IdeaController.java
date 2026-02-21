package io.github.drompincen.javaclawv1.gateway.controller;

import io.github.drompincen.javaclawv1.persistence.document.ThingDocument;
import io.github.drompincen.javaclawv1.protocol.api.CreateIdeaRequest;
import io.github.drompincen.javaclawv1.protocol.api.IdeaDto;
import io.github.drompincen.javaclawv1.protocol.api.ThingCategory;
import io.github.drompincen.javaclawv1.protocol.api.TicketDto;
import io.github.drompincen.javaclawv1.runtime.thing.ThingService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/projects/{projectId}/ideas")
public class IdeaController {

    private final ThingService thingService;

    public IdeaController(ThingService thingService) {
        this.thingService = thingService;
    }

    @PostMapping
    public ResponseEntity<IdeaDto> create(@PathVariable String projectId, @RequestBody CreateIdeaRequest req) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("title", req.title());
        payload.put("content", req.content());
        payload.put("tags", req.tags());
        payload.put("status", IdeaDto.IdeaStatus.NEW.name());
        ThingDocument thing = thingService.createThing(projectId, ThingCategory.IDEA, payload);
        return ResponseEntity.ok(toDto(thing));
    }

    @GetMapping
    public List<IdeaDto> list(@PathVariable String projectId) {
        return thingService.findByProjectAndCategory(projectId, ThingCategory.IDEA).stream()
                .map(this::toDto).collect(Collectors.toList());
    }

    @GetMapping("/{ideaId}")
    public ResponseEntity<IdeaDto> get(@PathVariable String projectId, @PathVariable String ideaId) {
        return thingService.findById(ideaId, ThingCategory.IDEA)
                .map(d -> ResponseEntity.ok(toDto(d)))
                .orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("/{ideaId}")
    public ResponseEntity<IdeaDto> update(@PathVariable String projectId, @PathVariable String ideaId,
                                          @RequestBody Map<String, Object> updates) {
        return thingService.findById(ideaId, ThingCategory.IDEA).map(existing -> {
            Map<String, Object> merged = new LinkedHashMap<>();
            if (updates.get("title") != null) merged.put("title", updates.get("title"));
            if (updates.get("content") != null) merged.put("content", updates.get("content"));
            if (updates.get("tags") != null) merged.put("tags", updates.get("tags"));
            if (updates.get("status") != null) merged.put("status", updates.get("status").toString());
            ThingDocument updated = thingService.mergePayload(existing, merged);
            return ResponseEntity.ok(toDto(updated));
        }).orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/{ideaId}/promote")
    public ResponseEntity<?> promote(@PathVariable String projectId, @PathVariable String ideaId) {
        return thingService.findById(ideaId, ThingCategory.IDEA).map(idea -> {
            Map<String, Object> p = idea.getPayload();

            // Create ticket via ThingService
            Map<String, Object> ticketPayload = new LinkedHashMap<>();
            ticketPayload.put("title", p.get("title"));
            ticketPayload.put("description", p.get("content"));
            ticketPayload.put("status", TicketDto.TicketStatus.TODO.name());
            ticketPayload.put("priority", TicketDto.TicketPriority.MEDIUM.name());
            ThingDocument ticket = thingService.createThing(projectId, ThingCategory.TICKET, ticketPayload);

            // Update idea status
            thingService.mergePayload(idea, Map.of(
                    "status", IdeaDto.IdeaStatus.PROMOTED.name(),
                    "promotedToTicketId", ticket.getId()
            ));

            return ResponseEntity.ok(Map.of("ticketId", ticket.getId(), "ideaId", ideaId));
        }).orElse(ResponseEntity.notFound().build());
    }

    private IdeaDto toDto(ThingDocument thing) {
        Map<String, Object> p = thing.getPayload();
        IdeaDto.IdeaStatus status = null;
        if (p.get("status") != null) {
            try { status = IdeaDto.IdeaStatus.valueOf(p.get("status").toString()); }
            catch (IllegalArgumentException ignored) {}
        }
        @SuppressWarnings("unchecked")
        List<String> tags = (List<String>) p.get("tags");
        return new IdeaDto(
                thing.getId(),
                thing.getProjectId(),
                (String) p.get("title"),
                (String) p.get("content"),
                tags,
                status,
                (String) p.get("promotedToTicketId"),
                (String) p.get("threadId"),
                (String) p.get("sourceUploadId"),
                thing.getCreateDate(),
                thing.getUpdateDate()
        );
    }
}
