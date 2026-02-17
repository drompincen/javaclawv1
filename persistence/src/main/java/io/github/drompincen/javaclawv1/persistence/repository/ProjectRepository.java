package io.github.drompincen.javaclawv1.persistence.repository;

import io.github.drompincen.javaclawv1.persistence.document.ProjectDocument;
import io.github.drompincen.javaclawv1.protocol.api.ProjectDto;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

public interface ProjectRepository extends MongoRepository<ProjectDocument, String> {
    List<ProjectDocument> findByStatus(ProjectDto.ProjectStatus status);
    List<ProjectDocument> findAllByOrderByUpdatedAtDesc();
    Optional<ProjectDocument> findByNameIgnoreCase(String name);
}
