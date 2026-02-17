package io.github.drompincen.javaclawv1.gateway.controller;

import io.github.drompincen.javaclawv1.persistence.document.SpecDocument;
import io.github.drompincen.javaclawv1.persistence.repository.SpecRepository;
import io.github.drompincen.javaclawv1.protocol.api.CreateSpecRequest;
import io.github.drompincen.javaclawv1.protocol.api.SpecDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/specs")
public class SpecController {

    private final SpecRepository specRepository;
    private final ObjectMapper objectMapper;

    public SpecController(SpecRepository specRepository, ObjectMapper objectMapper) {
        this.specRepository = specRepository;
        this.objectMapper = objectMapper;
    }

    @GetMapping
    public List<?> list(@RequestParam(required = false) String tag,
                        @RequestParam(required = false) String q) {
        if (tag != null && q != null) return specRepository.findByTagsContainingAndTitleContainingIgnoreCase(tag, q);
        if (tag != null) return specRepository.findByTagsContaining(tag);
        if (q != null) return specRepository.searchByText(q);
        return specRepository.findAll();
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> get(@PathVariable String id) {
        return specRepository.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody CreateSpecRequest req) {
        SpecDocument doc = new SpecDocument();
        doc.setSpecId(UUID.randomUUID().toString());
        doc.setTitle(req.title());
        doc.setTags(req.tags());
        doc.setSource(req.source() != null ? req.source() : "editor");
        doc.setJsonBody(req.jsonBody());
        doc.setCreatedAt(Instant.now());
        doc.setUpdatedAt(Instant.now());
        specRepository.save(doc);
        return ResponseEntity.ok(doc);
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable String id, @RequestBody CreateSpecRequest req) {
        return specRepository.findById(id).map(doc -> {
            doc.setTitle(req.title());
            doc.setTags(req.tags());
            if (req.source() != null) doc.setSource(req.source());
            doc.setJsonBody(req.jsonBody());
            doc.setUpdatedAt(Instant.now());
            specRepository.save(doc);
            return ResponseEntity.ok(doc);
        }).orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/{id}/download")
    public ResponseEntity<?> download(@PathVariable String id) {
        return specRepository.findById(id).map(doc -> {
            try {
                byte[] json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(doc.getJsonBody());
                return ResponseEntity.ok()
                        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + doc.getTitle() + ".json\"")
                        .contentType(MediaType.APPLICATION_JSON).body(json);
            } catch (Exception e) {
                return ResponseEntity.internalServerError().build();
            }
        }).orElse(ResponseEntity.notFound().build());
    }
}
