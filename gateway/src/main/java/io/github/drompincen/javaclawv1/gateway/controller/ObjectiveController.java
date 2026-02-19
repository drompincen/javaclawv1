package io.github.drompincen.javaclawv1.gateway.controller;

import io.github.drompincen.javaclawv1.persistence.document.ObjectiveDocument;
import io.github.drompincen.javaclawv1.persistence.repository.ObjectiveRepository;
import io.github.drompincen.javaclawv1.protocol.api.ObjectiveDto;
import io.github.drompincen.javaclawv1.protocol.api.ObjectiveStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/projects/{projectId}/objectives")
public class ObjectiveController {

    private final ObjectiveRepository objectiveRepository;

    public ObjectiveController(ObjectiveRepository objectiveRepository) {
        this.objectiveRepository = objectiveRepository;
    }

    @PostMapping
    public ResponseEntity<ObjectiveDto> create(@PathVariable String projectId,
                                                @RequestBody ObjectiveDocument body) {
        body.setObjectiveId(UUID.randomUUID().toString());
        body.setProjectId(projectId);
        if (body.getStatus() == null) {
            body.setStatus(ObjectiveStatus.PROPOSED);
        }
        body.setCreatedAt(Instant.now());
        body.setUpdatedAt(Instant.now());
        objectiveRepository.save(body);
        return ResponseEntity.ok(toDto(body));
    }

    @GetMapping
    public List<ObjectiveDto> list(@PathVariable String projectId,
                                    @RequestParam(required = false) ObjectiveStatus status,
                                    @RequestParam(required = false) String sprintName) {
        List<ObjectiveDocument> docs;
        if (status != null) {
            docs = objectiveRepository.findByProjectIdAndStatus(projectId, status);
        } else if (sprintName != null) {
            docs = objectiveRepository.findByProjectIdAndSprintName(projectId, sprintName);
        } else {
            docs = objectiveRepository.findByProjectId(projectId);
        }
        return docs.stream().map(this::toDto).collect(Collectors.toList());
    }

    @GetMapping("/{objectiveId}")
    public ResponseEntity<ObjectiveDto> get(@PathVariable String projectId,
                                             @PathVariable String objectiveId) {
        return objectiveRepository.findById(objectiveId)
                .map(d -> ResponseEntity.ok(toDto(d)))
                .orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("/{objectiveId}")
    public ResponseEntity<ObjectiveDto> update(@PathVariable String projectId,
                                                @PathVariable String objectiveId,
                                                @RequestBody ObjectiveDocument updates) {
        return objectiveRepository.findById(objectiveId).map(existing -> {
            if (updates.getSprintName() != null) existing.setSprintName(updates.getSprintName());
            if (updates.getOutcome() != null) existing.setOutcome(updates.getOutcome());
            if (updates.getMeasurableSignal() != null) existing.setMeasurableSignal(updates.getMeasurableSignal());
            if (updates.getRisks() != null) existing.setRisks(updates.getRisks());
            if (updates.getThreadIds() != null) existing.setThreadIds(updates.getThreadIds());
            if (updates.getTicketIds() != null) existing.setTicketIds(updates.getTicketIds());
            if (updates.getCoveragePercent() != null) existing.setCoveragePercent(updates.getCoveragePercent());
            if (updates.getStatus() != null) existing.setStatus(updates.getStatus());
            if (updates.getStartDate() != null) existing.setStartDate(updates.getStartDate());
            if (updates.getEndDate() != null) existing.setEndDate(updates.getEndDate());
            existing.setUpdatedAt(Instant.now());
            objectiveRepository.save(existing);
            return ResponseEntity.ok(toDto(existing));
        }).orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{objectiveId}")
    public ResponseEntity<Void> delete(@PathVariable String projectId,
                                        @PathVariable String objectiveId) {
        if (objectiveRepository.existsById(objectiveId)) {
            objectiveRepository.deleteById(objectiveId);
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.notFound().build();
    }

    private ObjectiveDto toDto(ObjectiveDocument doc) {
        return new ObjectiveDto(doc.getObjectiveId(), doc.getProjectId(), doc.getSprintName(),
                doc.getOutcome(), doc.getMeasurableSignal(), doc.getRisks(),
                doc.getThreadIds(), doc.getTicketIds(), doc.getCoveragePercent(),
                doc.getStatus(), doc.getStartDate(), doc.getEndDate(),
                doc.getCreatedAt(), doc.getUpdatedAt());
    }
}
