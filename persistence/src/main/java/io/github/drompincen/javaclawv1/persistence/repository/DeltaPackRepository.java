package io.github.drompincen.javaclawv1.persistence.repository;

import io.github.drompincen.javaclawv1.persistence.document.DeltaPackDocument;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface DeltaPackRepository extends MongoRepository<DeltaPackDocument, String> {
    List<DeltaPackDocument> findByProjectIdOrderByCreatedAtDesc(String projectId);
    List<DeltaPackDocument> findByProjectIdAndStatus(String projectId, String status);
    List<DeltaPackDocument> findByReconcileSessionId(String reconcileSessionId);
}
