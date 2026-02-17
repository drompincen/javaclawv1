package io.github.drompincen.javaclawv1.gateway.controller;

import io.github.drompincen.javaclawv1.persistence.document.MemoryDocument;
import io.github.drompincen.javaclawv1.persistence.repository.MemoryRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/memories")
public class MemoryController {

    private final MemoryRepository memoryRepository;

    public MemoryController(MemoryRepository memoryRepository) {
        this.memoryRepository = memoryRepository;
    }

    @GetMapping
    public List<MemoryDocument> list(
            @RequestParam(required = false) String scope,
            @RequestParam(required = false) String projectId,
            @RequestParam(required = false) String query) {

        if (query != null && !query.isBlank()) {
            return memoryRepository.searchContent(query);
        }
        if (scope != null && projectId != null) {
            MemoryDocument.MemoryScope s = MemoryDocument.MemoryScope.valueOf(scope.toUpperCase());
            return memoryRepository.findByScopeAndProjectId(s, projectId);
        }
        if (scope != null) {
            return memoryRepository.findByScope(MemoryDocument.MemoryScope.valueOf(scope.toUpperCase()));
        }
        return memoryRepository.findAll();
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> get(@PathVariable String id) {
        return memoryRepository.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody Map<String, Object> body) {
        MemoryDocument doc = new MemoryDocument();
        doc.setMemoryId(UUID.randomUUID().toString());
        doc.setScope(MemoryDocument.MemoryScope.valueOf(((String) body.getOrDefault("scope", "GLOBAL")).toUpperCase()));
        doc.setKey((String) body.get("key"));
        doc.setContent((String) body.get("content"));
        doc.setProjectId((String) body.get("projectId"));
        doc.setSessionId((String) body.get("sessionId"));
        doc.setCreatedBy((String) body.getOrDefault("createdBy", "user"));
        if (body.get("tags") instanceof List<?> tags) {
            doc.setTags(tags.stream().map(Object::toString).toList());
        }
        doc.setCreatedAt(Instant.now());
        doc.setUpdatedAt(Instant.now());
        memoryRepository.save(doc);
        return ResponseEntity.ok(doc);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable String id) {
        memoryRepository.deleteById(id);
        return ResponseEntity.noContent().build();
    }
}
