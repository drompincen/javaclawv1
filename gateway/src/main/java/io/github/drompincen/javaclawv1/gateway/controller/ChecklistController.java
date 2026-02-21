package io.github.drompincen.javaclawv1.gateway.controller;

import io.github.drompincen.javaclawv1.persistence.document.ThingDocument;
import io.github.drompincen.javaclawv1.protocol.api.ChecklistDto;
import io.github.drompincen.javaclawv1.protocol.api.ChecklistStatus;
import io.github.drompincen.javaclawv1.protocol.api.ThingCategory;
import io.github.drompincen.javaclawv1.runtime.thing.ThingService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/projects/{projectId}/checklists")
public class ChecklistController {

    private final ThingService thingService;

    public ChecklistController(ThingService thingService) {
        this.thingService = thingService;
    }

    @PostMapping
    public ResponseEntity<ChecklistDto> create(@PathVariable String projectId,
                                                @RequestBody Map<String, Object> body) {
        if (body.get("status") == null) {
            body.put("status", ChecklistStatus.IN_PROGRESS.name());
        }
        ThingDocument thing = thingService.createThing(projectId, ThingCategory.CHECKLIST, body);
        return ResponseEntity.ok(toDto(thing));
    }

    @GetMapping
    public List<ChecklistDto> list(@PathVariable String projectId,
                                    @RequestParam(required = false) ChecklistStatus status) {
        List<ThingDocument> docs = (status != null)
                ? thingService.findByProjectCategoryAndPayload(projectId, ThingCategory.CHECKLIST,
                        "status", status.name())
                : thingService.findByProjectAndCategory(projectId, ThingCategory.CHECKLIST);
        return docs.stream().map(this::toDto).collect(Collectors.toList());
    }

    @GetMapping("/{checklistId}")
    public ResponseEntity<ChecklistDto> get(@PathVariable String projectId,
                                             @PathVariable String checklistId) {
        return thingService.findById(checklistId, ThingCategory.CHECKLIST)
                .map(d -> ResponseEntity.ok(toDto(d)))
                .orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("/{checklistId}")
    public ResponseEntity<ChecklistDto> update(@PathVariable String projectId,
                                                @PathVariable String checklistId,
                                                @RequestBody Map<String, Object> updates) {
        return thingService.findById(checklistId, ThingCategory.CHECKLIST).map(existing -> {
            Map<String, Object> merged = new LinkedHashMap<>();
            if (updates.get("name") != null) merged.put("name", updates.get("name"));
            if (updates.get("items") != null) merged.put("items", updates.get("items"));
            if (updates.get("status") != null) merged.put("status", updates.get("status").toString());
            if (updates.get("ticketIds") != null) merged.put("ticketIds", updates.get("ticketIds"));
            ThingDocument updated = thingService.mergePayload(existing, merged);
            return ResponseEntity.ok(toDto(updated));
        }).orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{checklistId}")
    public ResponseEntity<Void> delete(@PathVariable String projectId,
                                        @PathVariable String checklistId) {
        if (thingService.existsById(checklistId)) {
            thingService.deleteById(checklistId);
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.notFound().build();
    }

    @SuppressWarnings("unchecked")
    private ChecklistDto toDto(ThingDocument thing) {
        Map<String, Object> p = thing.getPayload();
        ChecklistStatus status = null;
        if (p.get("status") != null) {
            try { status = ChecklistStatus.valueOf(p.get("status").toString()); }
            catch (IllegalArgumentException ignored) {}
        }
        List<Map<String, Object>> itemMaps = (List<Map<String, Object>>) p.get("items");
        List<ChecklistDto.ChecklistItem> items = itemMaps != null
                ? itemMaps.stream().map(m -> new ChecklistDto.ChecklistItem(
                        (String) m.get("itemId"), (String) m.get("text"),
                        (String) m.get("assignee"),
                        Boolean.TRUE.equals(m.get("checked")),
                        (String) m.get("notes"), (String) m.get("linkedTicketId")))
                .collect(Collectors.toList())
                : List.of();
        return new ChecklistDto(thing.getId(), thing.getProjectId(),
                (String) p.get("name"), (String) p.get("templateId"),
                (String) p.get("phaseId"), (List<String>) p.get("ticketIds"),
                items, status, (String) p.get("sourceThreadId"),
                thing.getCreateDate(), thing.getUpdateDate());
    }
}
