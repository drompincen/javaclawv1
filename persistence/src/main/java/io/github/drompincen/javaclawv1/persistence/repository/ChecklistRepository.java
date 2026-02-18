package io.github.drompincen.javaclawv1.persistence.repository;

import io.github.drompincen.javaclawv1.persistence.document.ChecklistDocument;
import io.github.drompincen.javaclawv1.protocol.api.ChecklistStatus;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface ChecklistRepository extends MongoRepository<ChecklistDocument, String> {
    List<ChecklistDocument> findByProjectId(String projectId);
    List<ChecklistDocument> findByProjectIdAndStatus(String projectId, ChecklistStatus status);
    List<ChecklistDocument> findByPhaseId(String phaseId);
}
