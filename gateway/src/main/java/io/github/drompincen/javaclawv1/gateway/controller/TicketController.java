package io.github.drompincen.javaclawv1.gateway.controller;

import io.github.drompincen.javaclawv1.persistence.document.ThingDocument;
import io.github.drompincen.javaclawv1.protocol.api.CreateTicketRequest;
import io.github.drompincen.javaclawv1.protocol.api.ThingCategory;
import io.github.drompincen.javaclawv1.protocol.api.TicketDto;
import io.github.drompincen.javaclawv1.protocol.api.TicketType;
import io.github.drompincen.javaclawv1.runtime.thing.ThingService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/projects/{projectId}/tickets")
public class TicketController {

    private final ThingService thingService;

    public TicketController(ThingService thingService) {
        this.thingService = thingService;
    }

    @PostMapping
    public ResponseEntity<TicketDto> create(@PathVariable String projectId, @RequestBody CreateTicketRequest req) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("title", req.title());
        payload.put("description", req.description());
        payload.put("status", TicketDto.TicketStatus.TODO.name());
        payload.put("priority", req.priority() != null ? req.priority().name() : TicketDto.TicketPriority.MEDIUM.name());
        if (req.owner() != null && !req.owner().isBlank()) {
            payload.put("owner", req.owner());
        }
        if (req.storyPoints() != null) {
            payload.put("storyPoints", req.storyPoints());
        }
        ThingDocument thing = thingService.createThing(projectId, ThingCategory.TICKET, payload);
        return ResponseEntity.ok(toDto(thing));
    }

    @GetMapping
    public List<TicketDto> list(@PathVariable String projectId,
                                @RequestParam(required = false) TicketDto.TicketStatus status) {
        List<ThingDocument> docs = (status != null)
                ? thingService.findByProjectCategoryAndPayload(projectId, ThingCategory.TICKET,
                        "status", status.name())
                : thingService.findByProjectAndCategory(projectId, ThingCategory.TICKET);
        return docs.stream().map(this::toDto).collect(Collectors.toList());
    }

    @GetMapping("/{ticketId}")
    public ResponseEntity<TicketDto> get(@PathVariable String projectId, @PathVariable String ticketId) {
        return thingService.findById(ticketId, ThingCategory.TICKET)
                .map(d -> ResponseEntity.ok(toDto(d)))
                .orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("/{ticketId}")
    public ResponseEntity<TicketDto> update(@PathVariable String projectId, @PathVariable String ticketId,
                                            @RequestBody Map<String, Object> updates) {
        return thingService.findById(ticketId, ThingCategory.TICKET).map(existing -> {
            Map<String, Object> merged = new LinkedHashMap<>();
            if (updates.get("title") != null) merged.put("title", updates.get("title"));
            if (updates.get("description") != null) merged.put("description", updates.get("description"));
            if (updates.get("status") != null) merged.put("status", updates.get("status").toString());
            if (updates.get("priority") != null) merged.put("priority", updates.get("priority").toString());
            if (updates.get("assignedResourceId") != null) merged.put("assignedResourceId", updates.get("assignedResourceId"));
            if (updates.get("blockedBy") != null) merged.put("blockedBy", updates.get("blockedBy"));
            if (updates.get("owner") != null) merged.put("owner", updates.get("owner"));
            if (updates.get("storyPoints") != null) merged.put("storyPoints", updates.get("storyPoints"));
            ThingDocument updated = thingService.mergePayload(existing, merged);
            return ResponseEntity.ok(toDto(updated));
        }).orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{ticketId}")
    public ResponseEntity<Void> delete(@PathVariable String projectId, @PathVariable String ticketId) {
        if (thingService.existsById(ticketId)) {
            thingService.deleteById(ticketId);
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.notFound().build();
    }

    @SuppressWarnings("unchecked")
    private TicketDto toDto(ThingDocument thing) {
        Map<String, Object> p = thing.getPayload();
        TicketDto.TicketStatus status = null;
        if (p.get("status") != null) {
            try { status = TicketDto.TicketStatus.valueOf(p.get("status").toString()); }
            catch (IllegalArgumentException ignored) {}
        }
        TicketDto.TicketPriority priority = null;
        if (p.get("priority") != null) {
            try { priority = TicketDto.TicketPriority.valueOf(p.get("priority").toString()); }
            catch (IllegalArgumentException ignored) {}
        }
        TicketType type = null;
        if (p.get("type") != null) {
            try { type = TicketType.valueOf(p.get("type").toString()); }
            catch (IllegalArgumentException ignored) {}
        }
        Integer storyPoints = p.get("storyPoints") != null
                ? ((Number) p.get("storyPoints")).intValue() : null;
        List<String> linkedThreadIds = (List<String>) p.get("linkedThreadIds");
        List<String> blockedBy = (List<String>) p.get("blockedBy");
        List<String> objectiveIds = (List<String>) p.get("objectiveIds");
        List<String> evidenceLinks = (List<String>) p.get("evidenceLinks");
        Instant lastExternalSync = p.get("lastExternalSync") != null
                ? Instant.parse(p.get("lastExternalSync").toString()) : null;
        return new TicketDto(
                thing.getId(), thing.getProjectId(),
                (String) p.get("title"), (String) p.get("description"),
                status, priority, type, (String) p.get("parentTicketId"),
                (String) p.get("assignedResourceId"), linkedThreadIds, blockedBy,
                objectiveIds, (String) p.get("phaseId"), evidenceLinks,
                (String) p.get("externalRef"), (String) p.get("owner"), storyPoints,
                lastExternalSync,
                thing.getCreateDate(), thing.getUpdateDate());
    }
}
