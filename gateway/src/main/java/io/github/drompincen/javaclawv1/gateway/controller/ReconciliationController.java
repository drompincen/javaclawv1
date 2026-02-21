package io.github.drompincen.javaclawv1.gateway.controller;

import io.github.drompincen.javaclawv1.persistence.document.ThingDocument;
import io.github.drompincen.javaclawv1.protocol.api.ReconciliationDto;
import io.github.drompincen.javaclawv1.protocol.api.ReconciliationStatus;
import io.github.drompincen.javaclawv1.protocol.api.ThingCategory;
import io.github.drompincen.javaclawv1.runtime.thing.ThingService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/projects/{projectId}/reconciliations")
public class ReconciliationController {

    private final ThingService thingService;

    public ReconciliationController(ThingService thingService) {
        this.thingService = thingService;
    }

    @PostMapping
    public ResponseEntity<ReconciliationDto> create(@PathVariable String projectId,
                                                     @RequestBody Map<String, Object> body) {
        if (body.get("status") == null) {
            body.put("status", ReconciliationStatus.DRAFT.name());
        }
        ThingDocument thing = thingService.createThing(projectId, ThingCategory.RECONCILIATION, body);
        return ResponseEntity.ok(toDto(thing));
    }

    @GetMapping
    public List<ReconciliationDto> list(@PathVariable String projectId,
                                         @RequestParam(required = false) ReconciliationStatus status) {
        List<ThingDocument> docs;
        if (status != null) {
            docs = thingService.findByProjectCategoryAndPayload(projectId, ThingCategory.RECONCILIATION,
                    "status", status.name());
        } else {
            docs = thingService.findByProjectAndCategory(projectId, ThingCategory.RECONCILIATION);
        }
        return docs.stream().map(this::toDto).collect(Collectors.toList());
    }

    @GetMapping("/{reconciliationId}")
    public ResponseEntity<ReconciliationDto> get(@PathVariable String projectId,
                                                  @PathVariable String reconciliationId) {
        return thingService.findById(reconciliationId, ThingCategory.RECONCILIATION)
                .map(d -> ResponseEntity.ok(toDto(d)))
                .orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("/{reconciliationId}")
    public ResponseEntity<ReconciliationDto> update(@PathVariable String projectId,
                                                     @PathVariable String reconciliationId,
                                                     @RequestBody Map<String, Object> updates) {
        return thingService.findById(reconciliationId, ThingCategory.RECONCILIATION).map(existing -> {
            Map<String, Object> merged = new LinkedHashMap<>();
            if (updates.get("sourceUploadId") != null) merged.put("sourceUploadId", updates.get("sourceUploadId"));
            if (updates.get("sourceType") != null) merged.put("sourceType", updates.get("sourceType"));
            if (updates.get("mappings") != null) merged.put("mappings", updates.get("mappings"));
            if (updates.get("conflicts") != null) merged.put("conflicts", updates.get("conflicts"));
            if (updates.get("status") != null) merged.put("status", updates.get("status").toString());
            ThingDocument updated = thingService.mergePayload(existing, merged);
            return ResponseEntity.ok(toDto(updated));
        }).orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{reconciliationId}")
    public ResponseEntity<Void> delete(@PathVariable String projectId,
                                        @PathVariable String reconciliationId) {
        if (thingService.existsById(reconciliationId)) {
            thingService.deleteById(reconciliationId);
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.notFound().build();
    }

    @SuppressWarnings("unchecked")
    private ReconciliationDto toDto(ThingDocument thing) {
        Map<String, Object> p = thing.getPayload();
        ReconciliationStatus status = null;
        if (p.get("status") != null) {
            try { status = ReconciliationStatus.valueOf(p.get("status").toString()); }
            catch (IllegalArgumentException ignored) {}
        }
        List<Map<String, Object>> mappingMaps = (List<Map<String, Object>>) p.get("mappings");
        List<ReconciliationDto.MappingEntry> mappings = mappingMaps != null
                ? mappingMaps.stream()
                    .map(m -> new ReconciliationDto.MappingEntry(
                            (String) m.get("sourceRow"), (String) m.get("ticketId"), (String) m.get("matchType")))
                    .collect(Collectors.toList())
                : Collections.emptyList();
        List<Map<String, Object>> conflictMaps = (List<Map<String, Object>>) p.get("conflicts");
        List<ReconciliationDto.ConflictEntry> conflicts = conflictMaps != null
                ? conflictMaps.stream()
                    .map(c -> new ReconciliationDto.ConflictEntry(
                            (String) c.get("field"), (String) c.get("sourceValue"),
                            (String) c.get("ticketValue"), (String) c.get("resolution")))
                    .collect(Collectors.toList())
                : Collections.emptyList();
        return new ReconciliationDto(thing.getId(), thing.getProjectId(),
                (String) p.get("sourceUploadId"), (String) p.get("sourceType"),
                mappings, conflicts, status,
                thing.getCreateDate(), thing.getUpdateDate());
    }
}
