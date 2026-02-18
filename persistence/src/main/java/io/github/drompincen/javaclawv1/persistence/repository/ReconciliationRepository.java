package io.github.drompincen.javaclawv1.persistence.repository;

import io.github.drompincen.javaclawv1.persistence.document.ReconciliationDocument;
import io.github.drompincen.javaclawv1.protocol.api.ReconciliationStatus;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface ReconciliationRepository extends MongoRepository<ReconciliationDocument, String> {
    List<ReconciliationDocument> findByProjectId(String projectId);
    List<ReconciliationDocument> findByProjectIdAndStatus(String projectId, ReconciliationStatus status);
    List<ReconciliationDocument> findBySourceUploadId(String sourceUploadId);
}
