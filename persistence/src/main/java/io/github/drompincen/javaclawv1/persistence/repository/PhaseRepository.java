package io.github.drompincen.javaclawv1.persistence.repository;

import io.github.drompincen.javaclawv1.persistence.document.PhaseDocument;
import io.github.drompincen.javaclawv1.protocol.api.PhaseStatus;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface PhaseRepository extends MongoRepository<PhaseDocument, String> {
    List<PhaseDocument> findByProjectId(String projectId);
    List<PhaseDocument> findByProjectIdOrderBySortOrder(String projectId);
    List<PhaseDocument> findByProjectIdAndStatus(String projectId, PhaseStatus status);
}
