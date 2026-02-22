package io.github.drompincen.javaclawv1.gateway.controller;

import io.github.drompincen.javaclawv1.persistence.document.MemoryDocument;
import io.github.drompincen.javaclawv1.persistence.repository.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/projects")
@ConditionalOnProperty(name = "javaclaw.tutorial.enabled", havingValue = "true")
public class TutorialController {

    private final ProjectRepository projectRepository;
    private final ThingRepository thingRepository;
    private final ThreadRepository threadRepository;
    private final MemoryRepository memoryRepository;
    private final SessionRepository sessionRepository;

    public TutorialController(ProjectRepository projectRepository,
                              ThingRepository thingRepository,
                              ThreadRepository threadRepository,
                              MemoryRepository memoryRepository,
                              SessionRepository sessionRepository) {
        this.projectRepository = projectRepository;
        this.thingRepository = thingRepository;
        this.threadRepository = threadRepository;
        this.memoryRepository = memoryRepository;
        this.sessionRepository = sessionRepository;
    }

    @DeleteMapping("/{projectId}/data")
    public ResponseEntity<Map<String, Object>> deleteProjectData(@PathVariable String projectId) {
        if (!projectRepository.existsById(projectId)) {
            return ResponseEntity.notFound().build();
        }

        long things = thingRepository.findByProjectId(projectId).size();
        thingRepository.deleteByProjectId(projectId);

        long threads = threadRepository.findByProjectIdsOrderByUpdatedAtDesc(projectId).size();
        threadRepository.deleteByProjectIdsContaining(projectId);

        long memories = memoryRepository.findByScopeAndProjectId(MemoryDocument.MemoryScope.PROJECT, projectId).size();
        memoryRepository.deleteByProjectId(projectId);

        long sessions = sessionRepository.findByProjectId(projectId).size();
        sessionRepository.deleteByProjectId(projectId);

        return ResponseEntity.ok(Map.of(
                "projectId", projectId,
                "deleted", Map.of(
                        "things", things,
                        "threads", threads,
                        "memories", memories,
                        "sessions", sessions
                )
        ));
    }
}
