package io.github.drompincen.javaclawv1.gateway.controller;

import io.github.drompincen.javaclawv1.protocol.api.ExtractionRequest;
import io.github.drompincen.javaclawv1.protocol.api.ExtractionResponse;
import io.github.drompincen.javaclawv1.runtime.agent.ExtractionService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/projects/{projectId}")
public class ExtractionController {

    private final ExtractionService extractionService;

    public ExtractionController(ExtractionService extractionService) {
        this.extractionService = extractionService;
    }

    @PostMapping("/extract")
    public ResponseEntity<ExtractionResponse> startExtraction(
            @PathVariable String projectId,
            @RequestBody(required = false) ExtractionRequest request) {
        // If no body, create default request for all threads
        if (request == null) {
            request = new ExtractionRequest(projectId, null, null, false);
        } else {
            // Ensure projectId from path is used
            request = new ExtractionRequest(projectId, request.threadIds(), request.types(), request.dryRun());
        }

        ExtractionResponse response = extractionService.startExtraction(request);
        return ResponseEntity.accepted().body(response);
    }

    @GetMapping("/extractions/{extractionId}")
    public ResponseEntity<ExtractionResponse> getExtraction(
            @PathVariable String projectId,
            @PathVariable String extractionId) {
        return extractionService.getExtraction(extractionId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/extractions")
    public List<ExtractionResponse> listExtractions(@PathVariable String projectId) {
        return extractionService.listExtractions(projectId);
    }
}
