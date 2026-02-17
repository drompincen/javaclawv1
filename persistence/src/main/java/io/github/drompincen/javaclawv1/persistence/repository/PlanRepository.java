package io.github.drompincen.javaclawv1.persistence.repository;

import io.github.drompincen.javaclawv1.persistence.document.PlanDocument;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface PlanRepository extends MongoRepository<PlanDocument, String> {
    List<PlanDocument> findByProjectId(String projectId);
}
