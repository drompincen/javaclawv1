package io.github.drompincen.javaclawv1.persistence.repository;

import io.github.drompincen.javaclawv1.persistence.document.ScorecardDocument;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

public interface ScorecardRepository extends MongoRepository<ScorecardDocument, String> {
    Optional<ScorecardDocument> findByProjectId(String projectId);
}
