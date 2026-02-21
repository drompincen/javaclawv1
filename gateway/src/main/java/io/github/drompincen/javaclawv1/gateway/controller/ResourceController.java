package io.github.drompincen.javaclawv1.gateway.controller;

import io.github.drompincen.javaclawv1.persistence.document.ThingDocument;
import io.github.drompincen.javaclawv1.protocol.api.ResourceDto;
import io.github.drompincen.javaclawv1.protocol.api.ResourceAssignmentDto;
import io.github.drompincen.javaclawv1.protocol.api.ThingCategory;
import io.github.drompincen.javaclawv1.runtime.thing.ThingService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/resources")
public class ResourceController {

    private final ThingService thingService;

    public ResourceController(ThingService thingService) {
        this.thingService = thingService;
    }

    @PostMapping
    public ResponseEntity<ResourceDto> create(@RequestBody Map<String, Object> body) {
        String projectId = (String) body.get("projectId");
        Map<String, Object> payload = new LinkedHashMap<>(body);
        payload.remove("projectId");
        ThingDocument thing = thingService.createThing(projectId, ThingCategory.RESOURCE, payload);
        return ResponseEntity.ok(toDto(thing));
    }

    @GetMapping
    public List<ResourceDto> list() {
        return thingService.findByCategory(ThingCategory.RESOURCE).stream()
                .map(this::toDto).collect(Collectors.toList());
    }

    @GetMapping("/{id}")
    public ResponseEntity<ResourceDto> get(@PathVariable String id) {
        return thingService.findById(id, ThingCategory.RESOURCE)
                .map(d -> ResponseEntity.ok(toDto(d)))
                .orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("/{id}")
    public ResponseEntity<ResourceDto> update(@PathVariable String id, @RequestBody Map<String, Object> updates) {
        return thingService.findById(id, ThingCategory.RESOURCE).map(existing -> {
            Map<String, Object> merged = new LinkedHashMap<>();
            if (updates.get("name") != null) merged.put("name", updates.get("name"));
            if (updates.get("email") != null) merged.put("email", updates.get("email"));
            if (updates.get("role") != null) merged.put("role", updates.get("role"));
            if (updates.get("skills") != null) merged.put("skills", updates.get("skills"));
            if (updates.get("availability") != null) {
                double avail = ((Number) updates.get("availability")).doubleValue();
                if (avail > 0) merged.put("availability", avail);
            }
            ThingDocument updated = thingService.mergePayload(existing, merged);
            return ResponseEntity.ok(toDto(updated));
        }).orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        if (thingService.existsById(id)) {
            thingService.deleteById(id);
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.notFound().build();
    }

    @GetMapping("/{id}/assignments")
    public List<ResourceAssignmentDto> assignments(@PathVariable String id) {
        return thingService.findByPayloadField(ThingCategory.RESOURCE_ASSIGNMENT, "resourceId", id).stream()
                .map(this::toAssignmentDto).collect(Collectors.toList());
    }

    private ResourceDto toDto(ThingDocument thing) {
        Map<String, Object> p = thing.getPayload();
        ResourceDto.ResourceRole role = null;
        if (p.get("role") != null) {
            try { role = ResourceDto.ResourceRole.valueOf(p.get("role").toString()); }
            catch (IllegalArgumentException ignored) {}
        }
        @SuppressWarnings("unchecked")
        List<String> skills = (List<String>) p.get("skills");
        return new ResourceDto(
                thing.getId(),
                thing.getProjectId(),
                (String) p.get("name"),
                (String) p.get("email"),
                role,
                skills,
                p.get("capacity") != null ? ((Number) p.get("capacity")).intValue() : 0,
                p.get("availability") != null ? ((Number) p.get("availability")).doubleValue() : 1.0
        );
    }

    private ResourceAssignmentDto toAssignmentDto(ThingDocument thing) {
        Map<String, Object> p = thing.getPayload();
        return new ResourceAssignmentDto(
                thing.getId(),
                (String) p.get("resourceId"),
                (String) p.get("ticketId"),
                thing.getProjectId(),
                p.get("percentageAllocation") != null ? ((Number) p.get("percentageAllocation")).doubleValue() : 0
        );
    }
}
