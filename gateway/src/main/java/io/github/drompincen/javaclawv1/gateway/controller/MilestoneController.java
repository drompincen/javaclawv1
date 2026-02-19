package io.github.drompincen.javaclawv1.gateway.controller;

import io.github.drompincen.javaclawv1.persistence.document.MilestoneDocument;
import io.github.drompincen.javaclawv1.persistence.repository.MilestoneRepository;
import io.github.drompincen.javaclawv1.protocol.api.MilestoneDto;
import io.github.drompincen.javaclawv1.protocol.api.MilestoneStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/projects/{projectId}/milestones")
public class MilestoneController {

    private final MilestoneRepository milestoneRepository;

    public MilestoneController(MilestoneRepository milestoneRepository) {
        this.milestoneRepository = milestoneRepository;
    }

    @PostMapping
    public ResponseEntity<MilestoneDto> create(@PathVariable String projectId,
                                                @RequestBody MilestoneDocument body) {
        body.setMilestoneId(UUID.randomUUID().toString());
        body.setProjectId(projectId);
        if (body.getStatus() == null) {
            body.setStatus(MilestoneStatus.UPCOMING);
        }
        body.setCreatedAt(Instant.now());
        body.setUpdatedAt(Instant.now());
        milestoneRepository.save(body);
        return ResponseEntity.ok(toDto(body));
    }

    @GetMapping
    public List<MilestoneDto> list(@PathVariable String projectId,
                                    @RequestParam(required = false) MilestoneStatus status) {
        List<MilestoneDocument> docs;
        if (status != null) {
            docs = milestoneRepository.findByProjectIdAndStatus(projectId, status);
        } else {
            docs = milestoneRepository.findByProjectIdOrderByTargetDateAsc(projectId);
        }
        return docs.stream().map(this::toDto).collect(Collectors.toList());
    }

    @GetMapping("/{milestoneId}")
    public ResponseEntity<MilestoneDto> get(@PathVariable String projectId,
                                             @PathVariable String milestoneId) {
        return milestoneRepository.findById(milestoneId)
                .filter(d -> projectId.equals(d.getProjectId()))
                .map(d -> ResponseEntity.ok(toDto(d)))
                .orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("/{milestoneId}")
    public ResponseEntity<MilestoneDto> update(@PathVariable String projectId,
                                                @PathVariable String milestoneId,
                                                @RequestBody MilestoneDocument updates) {
        return milestoneRepository.findById(milestoneId)
                .filter(d -> projectId.equals(d.getProjectId()))
                .map(existing -> {
                    if (updates.getName() != null) existing.setName(updates.getName());
                    if (updates.getDescription() != null) existing.setDescription(updates.getDescription());
                    if (updates.getTargetDate() != null) existing.setTargetDate(updates.getTargetDate());
                    if (updates.getActualDate() != null) existing.setActualDate(updates.getActualDate());
                    if (updates.getStatus() != null) existing.setStatus(updates.getStatus());
                    if (updates.getPhaseId() != null) existing.setPhaseId(updates.getPhaseId());
                    if (updates.getObjectiveIds() != null) existing.setObjectiveIds(updates.getObjectiveIds());
                    if (updates.getTicketIds() != null) existing.setTicketIds(updates.getTicketIds());
                    if (updates.getOwner() != null) existing.setOwner(updates.getOwner());
                    if (updates.getDependencies() != null) existing.setDependencies(updates.getDependencies());
                    existing.setUpdatedAt(Instant.now());
                    milestoneRepository.save(existing);
                    return ResponseEntity.ok(toDto(existing));
                }).orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{milestoneId}")
    public ResponseEntity<Void> delete(@PathVariable String projectId,
                                        @PathVariable String milestoneId) {
        return milestoneRepository.findById(milestoneId)
                .filter(d -> projectId.equals(d.getProjectId()))
                .map(d -> {
                    milestoneRepository.deleteById(milestoneId);
                    return ResponseEntity.noContent().<Void>build();
                }).orElse(ResponseEntity.notFound().build());
    }

    private MilestoneDto toDto(MilestoneDocument doc) {
        return new MilestoneDto(doc.getMilestoneId(), doc.getProjectId(), doc.getName(),
                doc.getDescription(), doc.getTargetDate(), doc.getActualDate(),
                doc.getStatus(), doc.getPhaseId(), doc.getObjectiveIds(),
                doc.getTicketIds(), doc.getOwner(), doc.getDependencies(),
                doc.getCreatedAt(), doc.getUpdatedAt());
    }
}
