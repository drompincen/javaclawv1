package io.github.drompincen.javaclawv1.gateway.controller;

import io.github.drompincen.javaclawv1.persistence.document.ReconciliationDocument;
import io.github.drompincen.javaclawv1.persistence.repository.ReconciliationRepository;
import io.github.drompincen.javaclawv1.protocol.api.ReconciliationDto;
import io.github.drompincen.javaclawv1.protocol.api.ReconciliationStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/projects/{projectId}/reconciliations")
public class ReconciliationController {

    private final ReconciliationRepository reconciliationRepository;

    public ReconciliationController(ReconciliationRepository reconciliationRepository) {
        this.reconciliationRepository = reconciliationRepository;
    }

    @PostMapping
    public ResponseEntity<ReconciliationDto> create(@PathVariable String projectId,
                                                     @RequestBody ReconciliationDocument body) {
        body.setReconciliationId(UUID.randomUUID().toString());
        body.setProjectId(projectId);
        if (body.getStatus() == null) {
            body.setStatus(ReconciliationStatus.DRAFT);
        }
        body.setCreatedAt(Instant.now());
        body.setUpdatedAt(Instant.now());
        reconciliationRepository.save(body);
        return ResponseEntity.ok(toDto(body));
    }

    @GetMapping
    public List<ReconciliationDto> list(@PathVariable String projectId,
                                         @RequestParam(required = false) ReconciliationStatus status) {
        List<ReconciliationDocument> docs;
        if (status != null) {
            docs = reconciliationRepository.findByProjectIdAndStatus(projectId, status);
        } else {
            docs = reconciliationRepository.findByProjectId(projectId);
        }
        return docs.stream().map(this::toDto).collect(Collectors.toList());
    }

    @GetMapping("/{reconciliationId}")
    public ResponseEntity<ReconciliationDto> get(@PathVariable String projectId,
                                                  @PathVariable String reconciliationId) {
        return reconciliationRepository.findById(reconciliationId)
                .map(d -> ResponseEntity.ok(toDto(d)))
                .orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("/{reconciliationId}")
    public ResponseEntity<ReconciliationDto> update(@PathVariable String projectId,
                                                     @PathVariable String reconciliationId,
                                                     @RequestBody ReconciliationDocument updates) {
        return reconciliationRepository.findById(reconciliationId).map(existing -> {
            if (updates.getSourceUploadId() != null) existing.setSourceUploadId(updates.getSourceUploadId());
            if (updates.getSourceType() != null) existing.setSourceType(updates.getSourceType());
            if (updates.getMappings() != null) existing.setMappings(updates.getMappings());
            if (updates.getConflicts() != null) existing.setConflicts(updates.getConflicts());
            if (updates.getStatus() != null) existing.setStatus(updates.getStatus());
            existing.setUpdatedAt(Instant.now());
            reconciliationRepository.save(existing);
            return ResponseEntity.ok(toDto(existing));
        }).orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{reconciliationId}")
    public ResponseEntity<Void> delete(@PathVariable String projectId,
                                        @PathVariable String reconciliationId) {
        if (reconciliationRepository.existsById(reconciliationId)) {
            reconciliationRepository.deleteById(reconciliationId);
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.notFound().build();
    }

    private ReconciliationDto toDto(ReconciliationDocument doc) {
        List<ReconciliationDto.MappingEntry> mappings = doc.getMappings() != null
                ? doc.getMappings().stream()
                    .map(m -> new ReconciliationDto.MappingEntry(m.getSourceRow(), m.getTicketId(), m.getMatchType()))
                    .collect(Collectors.toList())
                : Collections.emptyList();
        List<ReconciliationDto.ConflictEntry> conflicts = doc.getConflicts() != null
                ? doc.getConflicts().stream()
                    .map(c -> new ReconciliationDto.ConflictEntry(c.getField(), c.getSourceValue(), c.getTicketValue(), c.getResolution()))
                    .collect(Collectors.toList())
                : Collections.emptyList();
        return new ReconciliationDto(doc.getReconciliationId(), doc.getProjectId(),
                doc.getSourceUploadId(), doc.getSourceType(),
                mappings, conflicts,
                doc.getStatus(), doc.getCreatedAt(), doc.getUpdatedAt());
    }
}
