package io.github.drompincen.javaclawv1.gateway.controller;

import io.github.drompincen.javaclawv1.persistence.document.ThingDocument;
import io.github.drompincen.javaclawv1.protocol.api.LinkDto;
import io.github.drompincen.javaclawv1.protocol.api.ThingCategory;
import io.github.drompincen.javaclawv1.runtime.thing.ThingService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/projects/{projectId}/links")
public class LinkController {

    private final ThingService thingService;

    public LinkController(ThingService thingService) {
        this.thingService = thingService;
    }

    @PostMapping
    public ResponseEntity<LinkDto> create(@PathVariable String projectId,
                                           @RequestBody Map<String, Object> body) {
        ThingDocument thing = thingService.createThing(projectId, ThingCategory.LINK, body);
        return ResponseEntity.ok(toDto(thing));
    }

    @GetMapping
    public List<LinkDto> list(@PathVariable String projectId,
                               @RequestParam(required = false) String category,
                               @RequestParam(required = false) String bundleId) {
        List<ThingDocument> docs;
        if (category != null) {
            docs = thingService.findByProjectCategoryAndPayload(projectId, ThingCategory.LINK,
                    "category", category);
        } else if (bundleId != null) {
            docs = thingService.findByPayloadField(ThingCategory.LINK, "bundleId", bundleId);
        } else {
            docs = thingService.findByProjectAndCategory(projectId, ThingCategory.LINK);
        }
        return docs.stream().map(this::toDto).collect(Collectors.toList());
    }

    @GetMapping("/{linkId}")
    public ResponseEntity<LinkDto> get(@PathVariable String projectId,
                                        @PathVariable String linkId) {
        return thingService.findById(linkId, ThingCategory.LINK)
                .map(d -> ResponseEntity.ok(toDto(d)))
                .orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("/{linkId}")
    public ResponseEntity<LinkDto> update(@PathVariable String projectId,
                                           @PathVariable String linkId,
                                           @RequestBody Map<String, Object> updates) {
        return thingService.findById(linkId, ThingCategory.LINK).map(existing -> {
            Map<String, Object> merged = new LinkedHashMap<>();
            if (updates.get("url") != null) merged.put("url", updates.get("url"));
            if (updates.get("title") != null) merged.put("title", updates.get("title"));
            if (updates.get("category") != null) merged.put("category", updates.get("category"));
            if (updates.get("description") != null) merged.put("description", updates.get("description"));
            if (updates.get("bundleId") != null) merged.put("bundleId", updates.get("bundleId"));
            if (updates.get("threadIds") != null) merged.put("threadIds", updates.get("threadIds"));
            if (updates.get("objectiveIds") != null) merged.put("objectiveIds", updates.get("objectiveIds"));
            if (updates.get("phaseIds") != null) merged.put("phaseIds", updates.get("phaseIds"));
            if (updates.get("tags") != null) merged.put("tags", updates.get("tags"));
            merged.put("pinned", Boolean.TRUE.equals(updates.get("pinned")));
            ThingDocument updated = thingService.mergePayload(existing, merged);
            return ResponseEntity.ok(toDto(updated));
        }).orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{linkId}")
    public ResponseEntity<Void> delete(@PathVariable String projectId,
                                        @PathVariable String linkId) {
        if (thingService.existsById(linkId)) {
            thingService.deleteById(linkId);
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.notFound().build();
    }

    @SuppressWarnings("unchecked")
    private LinkDto toDto(ThingDocument thing) {
        Map<String, Object> p = thing.getPayload();
        return new LinkDto(thing.getId(), thing.getProjectId(),
                (String) p.get("url"), (String) p.get("title"),
                (String) p.get("category"), (String) p.get("description"),
                Boolean.TRUE.equals(p.get("pinned")), (String) p.get("bundleId"),
                (List<String>) p.get("threadIds"), (List<String>) p.get("objectiveIds"),
                (List<String>) p.get("phaseIds"), (List<String>) p.get("tags"),
                thing.getCreateDate(), thing.getUpdateDate());
    }
}
