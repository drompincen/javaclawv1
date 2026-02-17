package io.github.drompincen.javaclawv1.gateway.controller;

import io.github.drompincen.javaclawv1.persistence.document.DesignDocument;
import io.github.drompincen.javaclawv1.persistence.repository.DesignRepository;
import io.github.drompincen.javaclawv1.protocol.api.DesignDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/projects/{projectId}/designs")
public class DesignController {

    private final DesignRepository designRepository;
    private final ObjectMapper objectMapper;

    public DesignController(DesignRepository designRepository, ObjectMapper objectMapper) {
        this.designRepository = designRepository;
        this.objectMapper = objectMapper;
    }

    @PostMapping
    public ResponseEntity<DesignDto> create(@PathVariable String projectId, @RequestBody DesignDocument doc) {
        if (doc.getDesignId() == null) doc.setDesignId(UUID.randomUUID().toString());
        doc.setProjectId(projectId);
        doc.setCreatedAt(Instant.now());
        doc.setUpdatedAt(Instant.now());
        designRepository.save(doc);
        return ResponseEntity.ok(toDto(doc));
    }

    @GetMapping
    public List<DesignDto> list(@PathVariable String projectId) {
        return designRepository.findByProjectId(projectId).stream()
                .map(this::toDto).collect(Collectors.toList());
    }

    @GetMapping("/{designId}")
    public ResponseEntity<DesignDto> get(@PathVariable String projectId, @PathVariable String designId) {
        return designRepository.findById(designId)
                .map(d -> ResponseEntity.ok(toDto(d)))
                .orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("/{designId}")
    public ResponseEntity<DesignDto> update(@PathVariable String projectId, @PathVariable String designId,
                                            @RequestBody DesignDocument updates) {
        return designRepository.findById(designId).map(existing -> {
            if (updates.getTitle() != null) existing.setTitle(updates.getTitle());
            if (updates.getTags() != null) existing.setTags(updates.getTags());
            if (updates.getSource() != null) existing.setSource(updates.getSource());
            if (updates.getJsonBody() != null) existing.setJsonBody(updates.getJsonBody());
            existing.setUpdatedAt(Instant.now());
            designRepository.save(existing);
            return ResponseEntity.ok(toDto(existing));
        }).orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{designId}")
    public ResponseEntity<Void> delete(@PathVariable String projectId, @PathVariable String designId) {
        if (designRepository.existsById(designId)) {
            designRepository.deleteById(designId);
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.notFound().build();
    }

    private DesignDto toDto(DesignDocument doc) {
        return new DesignDto(doc.getDesignId(), doc.getProjectId(), doc.getTitle(),
                doc.getTags(), doc.getSource(),
                objectMapper.valueToTree(doc.getJsonBody()),
                doc.getCreatedAt(), doc.getUpdatedAt(), doc.getVersion());
    }
}
