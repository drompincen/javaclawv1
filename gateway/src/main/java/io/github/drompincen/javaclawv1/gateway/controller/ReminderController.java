package io.github.drompincen.javaclawv1.gateway.controller;

import io.github.drompincen.javaclawv1.persistence.document.ThingDocument;
import io.github.drompincen.javaclawv1.protocol.api.CreateReminderRequest;
import io.github.drompincen.javaclawv1.protocol.api.ReminderDto;
import io.github.drompincen.javaclawv1.protocol.api.ThingCategory;
import io.github.drompincen.javaclawv1.runtime.thing.ThingService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

@RestController
public class ReminderController {

    private final ThingService thingService;

    public ReminderController(ThingService thingService) {
        this.thingService = thingService;
    }

    @PostMapping("/api/projects/{projectId}/reminders")
    public ResponseEntity<ReminderDto> create(@PathVariable String projectId,
                                               @RequestBody CreateReminderRequest req) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("message", req.message());
        payload.put("type", req.type() != null ? req.type().name() : ReminderDto.ReminderType.TIME_BASED.name());
        if (req.triggerAt() != null) payload.put("triggerAt", req.triggerAt().toString());
        payload.put("triggered", false);
        payload.put("recurring", req.recurring());
        if (req.intervalSeconds() != null) payload.put("intervalSeconds", req.intervalSeconds());
        ThingDocument thing = thingService.createThing(projectId, ThingCategory.REMINDER, payload);
        return ResponseEntity.ok(toDto(thing));
    }

    @GetMapping("/api/projects/{projectId}/reminders")
    public List<ReminderDto> listByProject(@PathVariable String projectId) {
        return thingService.findByProjectAndCategory(projectId, ThingCategory.REMINDER).stream()
                .map(this::toDto).collect(Collectors.toList());
    }

    @GetMapping("/api/reminders")
    public List<ReminderDto> list(@RequestParam(required = false) String projectId) {
        List<ThingDocument> docs;
        if (projectId != null) {
            docs = thingService.findByProjectAndCategory(projectId, ThingCategory.REMINDER);
        } else {
            docs = thingService.findByCategory(ThingCategory.REMINDER);
        }
        return docs.stream().map(this::toDto).collect(Collectors.toList());
    }

    @DeleteMapping("/api/reminders/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        if (thingService.existsById(id)) {
            thingService.deleteById(id);
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.notFound().build();
    }

    private ReminderDto toDto(ThingDocument thing) {
        Map<String, Object> p = thing.getPayload();
        ReminderDto.ReminderType type = null;
        if (p.get("type") != null) {
            try { type = ReminderDto.ReminderType.valueOf(p.get("type").toString()); }
            catch (IllegalArgumentException ignored) {}
        }
        java.time.Instant triggerAt = null;
        if (p.get("triggerAt") != null) {
            try { triggerAt = java.time.Instant.parse(p.get("triggerAt").toString()); }
            catch (Exception ignored) {}
        }
        return new ReminderDto(
                thing.getId(),
                thing.getProjectId(),
                (String) p.get("message"),
                type,
                triggerAt,
                (String) p.get("condition"),
                Boolean.TRUE.equals(p.get("triggered")),
                Boolean.TRUE.equals(p.get("recurring")),
                p.get("intervalSeconds") != null ? ((Number) p.get("intervalSeconds")).longValue() : null,
                (String) p.get("sourceThreadId")
        );
    }
}
