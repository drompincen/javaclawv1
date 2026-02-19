package io.github.drompincen.javaclawv1.persistence.repository;

import io.github.drompincen.javaclawv1.persistence.document.BlindspotDocument;
import io.github.drompincen.javaclawv1.protocol.api.BlindspotCategory;
import io.github.drompincen.javaclawv1.protocol.api.BlindspotStatus;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface BlindspotRepository extends MongoRepository<BlindspotDocument, String> {
    List<BlindspotDocument> findByProjectId(String projectId);
    List<BlindspotDocument> findByProjectIdAndStatus(String projectId, BlindspotStatus status);
    List<BlindspotDocument> findByDeltaPackId(String deltaPackId);
    List<BlindspotDocument> findByProjectIdAndCategory(String projectId, BlindspotCategory category);
    long countByProjectIdAndStatus(String projectId, BlindspotStatus status);
}
