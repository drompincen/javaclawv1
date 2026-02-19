package io.github.drompincen.javaclawv1.gateway.controller;

import io.github.drompincen.javaclawv1.persistence.document.PhaseDocument;
import io.github.drompincen.javaclawv1.persistence.repository.PhaseRepository;
import io.github.drompincen.javaclawv1.protocol.api.PhaseDto;
import io.github.drompincen.javaclawv1.protocol.api.PhaseStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/projects/{projectId}/phases")
public class PhaseController {

    private final PhaseRepository phaseRepository;

    public PhaseController(PhaseRepository phaseRepository) {
        this.phaseRepository = phaseRepository;
    }

    @PostMapping
    public ResponseEntity<PhaseDto> create(@PathVariable String projectId,
                                            @RequestBody PhaseDocument body) {
        body.setPhaseId(UUID.randomUUID().toString());
        body.setProjectId(projectId);
        if (body.getStatus() == null) {
            body.setStatus(PhaseStatus.NOT_STARTED);
        }
        body.setCreatedAt(Instant.now());
        body.setUpdatedAt(Instant.now());
        phaseRepository.save(body);
        return ResponseEntity.ok(toDto(body));
    }

    @GetMapping
    public List<PhaseDto> list(@PathVariable String projectId,
                                @RequestParam(required = false) PhaseStatus status) {
        List<PhaseDocument> docs;
        if (status != null) {
            docs = phaseRepository.findByProjectIdAndStatus(projectId, status);
        } else {
            docs = phaseRepository.findByProjectIdOrderBySortOrder(projectId);
        }
        return docs.stream().map(this::toDto).collect(Collectors.toList());
    }

    @GetMapping("/{phaseId}")
    public ResponseEntity<PhaseDto> get(@PathVariable String projectId,
                                         @PathVariable String phaseId) {
        return phaseRepository.findById(phaseId)
                .map(d -> ResponseEntity.ok(toDto(d)))
                .orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("/{phaseId}")
    public ResponseEntity<PhaseDto> update(@PathVariable String projectId,
                                            @PathVariable String phaseId,
                                            @RequestBody PhaseDocument updates) {
        return phaseRepository.findById(phaseId).map(existing -> {
            if (updates.getName() != null) existing.setName(updates.getName());
            if (updates.getDescription() != null) existing.setDescription(updates.getDescription());
            if (updates.getEntryCriteria() != null) existing.setEntryCriteria(updates.getEntryCriteria());
            if (updates.getExitCriteria() != null) existing.setExitCriteria(updates.getExitCriteria());
            if (updates.getChecklistIds() != null) existing.setChecklistIds(updates.getChecklistIds());
            if (updates.getObjectiveIds() != null) existing.setObjectiveIds(updates.getObjectiveIds());
            if (updates.getStatus() != null) existing.setStatus(updates.getStatus());
            if (updates.getStartDate() != null) existing.setStartDate(updates.getStartDate());
            if (updates.getEndDate() != null) existing.setEndDate(updates.getEndDate());
            existing.setUpdatedAt(Instant.now());
            phaseRepository.save(existing);
            return ResponseEntity.ok(toDto(existing));
        }).orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{phaseId}")
    public ResponseEntity<Void> delete(@PathVariable String projectId,
                                        @PathVariable String phaseId) {
        if (phaseRepository.existsById(phaseId)) {
            phaseRepository.deleteById(phaseId);
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.notFound().build();
    }

    private PhaseDto toDto(PhaseDocument doc) {
        return new PhaseDto(doc.getPhaseId(), doc.getProjectId(), doc.getName(),
                doc.getDescription(), doc.getEntryCriteria(), doc.getExitCriteria(),
                doc.getChecklistIds(), doc.getObjectiveIds(), doc.getStatus(),
                doc.getSortOrder(), doc.getStartDate(), doc.getEndDate(),
                doc.getCreatedAt(), doc.getUpdatedAt());
    }
}
