package io.github.drompincen.javaclawv1.persistence.repository;

import io.github.drompincen.javaclawv1.persistence.document.IntakeDocument;
import io.github.drompincen.javaclawv1.protocol.api.IntakeSourceType;
import io.github.drompincen.javaclawv1.protocol.api.IntakeStatus;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface IntakeRepository extends MongoRepository<IntakeDocument, String> {
    List<IntakeDocument> findByProjectId(String projectId);
    List<IntakeDocument> findByStatus(IntakeStatus status);
    List<IntakeDocument> findBySourceType(IntakeSourceType type);
    List<IntakeDocument> findByProjectIdAndStatus(String projectId, IntakeStatus status);
}
