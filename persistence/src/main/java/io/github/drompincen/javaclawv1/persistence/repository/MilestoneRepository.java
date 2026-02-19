package io.github.drompincen.javaclawv1.persistence.repository;

import io.github.drompincen.javaclawv1.persistence.document.MilestoneDocument;
import io.github.drompincen.javaclawv1.protocol.api.MilestoneStatus;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface MilestoneRepository extends MongoRepository<MilestoneDocument, String> {
    List<MilestoneDocument> findByProjectIdOrderByTargetDateAsc(String projectId);
    List<MilestoneDocument> findByProjectIdAndStatus(String projectId, MilestoneStatus status);
    List<MilestoneDocument> findByPhaseId(String phaseId);
}
