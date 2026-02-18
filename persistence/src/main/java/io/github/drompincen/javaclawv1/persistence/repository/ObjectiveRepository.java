package io.github.drompincen.javaclawv1.persistence.repository;

import io.github.drompincen.javaclawv1.persistence.document.ObjectiveDocument;
import io.github.drompincen.javaclawv1.protocol.api.ObjectiveStatus;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface ObjectiveRepository extends MongoRepository<ObjectiveDocument, String> {
    List<ObjectiveDocument> findByProjectId(String projectId);
    List<ObjectiveDocument> findByProjectIdAndStatus(String projectId, ObjectiveStatus status);
    List<ObjectiveDocument> findByProjectIdAndSprintName(String projectId, String sprintName);
}
