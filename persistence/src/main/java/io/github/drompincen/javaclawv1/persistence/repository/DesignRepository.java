package io.github.drompincen.javaclawv1.persistence.repository;

import io.github.drompincen.javaclawv1.persistence.document.DesignDocument;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface DesignRepository extends MongoRepository<DesignDocument, String> {
    List<DesignDocument> findByProjectId(String projectId);
}
