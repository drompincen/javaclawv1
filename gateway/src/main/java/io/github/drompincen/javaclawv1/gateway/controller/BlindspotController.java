package io.github.drompincen.javaclawv1.gateway.controller;

import io.github.drompincen.javaclawv1.persistence.document.ThingDocument;
import io.github.drompincen.javaclawv1.protocol.api.BlindspotCategory;
import io.github.drompincen.javaclawv1.protocol.api.BlindspotDto;
import io.github.drompincen.javaclawv1.protocol.api.BlindspotSeverity;
import io.github.drompincen.javaclawv1.protocol.api.BlindspotStatus;
import io.github.drompincen.javaclawv1.protocol.api.ThingCategory;
import io.github.drompincen.javaclawv1.runtime.thing.ThingService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/projects/{projectId}/blindspots")
public class BlindspotController {

    private final ThingService thingService;

    public BlindspotController(ThingService thingService) {
        this.thingService = thingService;
    }

    @PostMapping
    public ResponseEntity<BlindspotDto> create(@PathVariable String projectId,
                                                @RequestBody Map<String, Object> body) {
        Map<String, Object> payload = new LinkedHashMap<>(body);
        if (!payload.containsKey("status") || payload.get("status") == null) {
            payload.put("status", BlindspotStatus.OPEN.name());
        } else {
            payload.put("status", payload.get("status").toString());
        }
        if (payload.get("category") != null) payload.put("category", payload.get("category").toString());
        if (payload.get("severity") != null) payload.put("severity", payload.get("severity").toString());
        ThingDocument thing = thingService.createThing(projectId, ThingCategory.BLINDSPOT, payload);
        return ResponseEntity.ok(toDto(thing));
    }

    @GetMapping
    public List<BlindspotDto> list(@PathVariable String projectId,
                                    @RequestParam(required = false) BlindspotStatus status,
                                    @RequestParam(required = false) BlindspotCategory category) {
        List<ThingDocument> docs;
        if (status != null) {
            docs = thingService.findByProjectCategoryAndPayload(projectId, ThingCategory.BLINDSPOT,
                    "status", status.name());
        } else if (category != null) {
            docs = thingService.findByProjectCategoryAndPayload(projectId, ThingCategory.BLINDSPOT,
                    "category", category.name());
        } else {
            docs = thingService.findByProjectAndCategory(projectId, ThingCategory.BLINDSPOT);
        }
        return docs.stream().map(this::toDto).collect(Collectors.toList());
    }

    @GetMapping("/{blindspotId}")
    public ResponseEntity<BlindspotDto> get(@PathVariable String projectId,
                                             @PathVariable String blindspotId) {
        return thingService.findById(blindspotId, ThingCategory.BLINDSPOT)
                .map(d -> ResponseEntity.ok(toDto(d)))
                .orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("/{blindspotId}")
    public ResponseEntity<BlindspotDto> update(@PathVariable String projectId,
                                                @PathVariable String blindspotId,
                                                @RequestBody Map<String, Object> updates) {
        return thingService.findById(blindspotId, ThingCategory.BLINDSPOT).map(existing -> {
            Map<String, Object> merged = new LinkedHashMap<>();
            if (updates.get("status") != null) {
                String newStatus = updates.get("status").toString();
                merged.put("status", newStatus);
                if (newStatus.equals(BlindspotStatus.RESOLVED.name())
                        && existing.getPayload().get("resolvedAt") == null) {
                    merged.put("resolvedAt", Instant.now().toString());
                }
            }
            if (updates.get("owner") != null) merged.put("owner", updates.get("owner"));
            ThingDocument updated = thingService.mergePayload(existing, merged);
            return ResponseEntity.ok(toDto(updated));
        }).orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{blindspotId}")
    public ResponseEntity<Void> delete(@PathVariable String projectId,
                                        @PathVariable String blindspotId) {
        return thingService.findById(blindspotId, ThingCategory.BLINDSPOT).map(doc -> {
            thingService.deleteById(doc.getId());
            return ResponseEntity.noContent().<Void>build();
        }).orElse(ResponseEntity.notFound().build());
    }

    @SuppressWarnings("unchecked")
    private BlindspotDto toDto(ThingDocument thing) {
        Map<String, Object> p = thing.getPayload();
        BlindspotCategory category = null;
        if (p.get("category") != null) {
            try { category = BlindspotCategory.valueOf(p.get("category").toString()); }
            catch (IllegalArgumentException ignored) {}
        }
        BlindspotSeverity severity = null;
        if (p.get("severity") != null) {
            try { severity = BlindspotSeverity.valueOf(p.get("severity").toString()); }
            catch (IllegalArgumentException ignored) {}
        }
        BlindspotStatus status = null;
        if (p.get("status") != null) {
            try { status = BlindspotStatus.valueOf(p.get("status").toString()); }
            catch (IllegalArgumentException ignored) {}
        }
        Instant resolvedAt = p.get("resolvedAt") != null
                ? Instant.parse(p.get("resolvedAt").toString()) : null;
        List<Map<String, String>> sourceRefs = (List<Map<String, String>>) p.get("sourceRefs");
        return new BlindspotDto(
                thing.getId(), thing.getProjectId(),
                thing.getProjectName(),
                (String) p.get("title"), (String) p.get("description"),
                category, severity, status,
                (String) p.get("owner"), sourceRefs, (String) p.get("deltaPackId"),
                (String) p.get("reconcileRunId"),
                thing.getCreateDate(), thing.getUpdateDate(), resolvedAt);
    }
}
