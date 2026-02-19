package io.github.drompincen.javaclawv1.gateway.controller;

import io.github.drompincen.javaclawv1.persistence.document.ChecklistDocument;
import io.github.drompincen.javaclawv1.persistence.repository.ChecklistRepository;
import io.github.drompincen.javaclawv1.protocol.api.ChecklistDto;
import io.github.drompincen.javaclawv1.protocol.api.ChecklistStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/projects/{projectId}/checklists")
public class ChecklistController {

    private final ChecklistRepository checklistRepository;

    public ChecklistController(ChecklistRepository checklistRepository) {
        this.checklistRepository = checklistRepository;
    }

    @PostMapping
    public ResponseEntity<ChecklistDto> create(@PathVariable String projectId,
                                                @RequestBody ChecklistDocument body) {
        body.setChecklistId(UUID.randomUUID().toString());
        body.setProjectId(projectId);
        if (body.getStatus() == null) {
            body.setStatus(ChecklistStatus.IN_PROGRESS);
        }
        body.setCreatedAt(Instant.now());
        body.setUpdatedAt(Instant.now());
        checklistRepository.save(body);
        return ResponseEntity.ok(toDto(body));
    }

    @GetMapping
    public List<ChecklistDto> list(@PathVariable String projectId,
                                    @RequestParam(required = false) ChecklistStatus status) {
        List<ChecklistDocument> docs = (status != null)
                ? checklistRepository.findByProjectIdAndStatus(projectId, status)
                : checklistRepository.findByProjectId(projectId);
        return docs.stream().map(this::toDto).collect(Collectors.toList());
    }

    @GetMapping("/{checklistId}")
    public ResponseEntity<ChecklistDto> get(@PathVariable String projectId,
                                             @PathVariable String checklistId) {
        return checklistRepository.findById(checklistId)
                .map(d -> ResponseEntity.ok(toDto(d)))
                .orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("/{checklistId}")
    public ResponseEntity<ChecklistDto> update(@PathVariable String projectId,
                                                @PathVariable String checklistId,
                                                @RequestBody ChecklistDocument updates) {
        return checklistRepository.findById(checklistId).map(existing -> {
            if (updates.getName() != null) existing.setName(updates.getName());
            if (updates.getItems() != null) existing.setItems(updates.getItems());
            if (updates.getStatus() != null) existing.setStatus(updates.getStatus());
            if (updates.getTicketIds() != null) existing.setTicketIds(updates.getTicketIds());
            existing.setUpdatedAt(Instant.now());
            checklistRepository.save(existing);
            return ResponseEntity.ok(toDto(existing));
        }).orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{checklistId}")
    public ResponseEntity<Void> delete(@PathVariable String projectId,
                                        @PathVariable String checklistId) {
        if (checklistRepository.existsById(checklistId)) {
            checklistRepository.deleteById(checklistId);
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.notFound().build();
    }

    private ChecklistDto toDto(ChecklistDocument doc) {
        List<ChecklistDto.ChecklistItem> items = doc.getItems() != null
                ? doc.getItems().stream().map(i -> new ChecklistDto.ChecklistItem(
                        i.getItemId(), i.getText(), i.getAssignee(),
                        i.isChecked(), i.getNotes(), i.getLinkedTicketId()))
                .collect(Collectors.toList())
                : List.of();

        return new ChecklistDto(doc.getChecklistId(), doc.getProjectId(), doc.getName(),
                doc.getTemplateId(), doc.getPhaseId(), doc.getTicketIds(), items,
                doc.getStatus(), doc.getSourceThreadId(), doc.getCreatedAt(), doc.getUpdatedAt());
    }
}
