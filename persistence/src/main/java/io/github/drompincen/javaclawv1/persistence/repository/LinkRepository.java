package io.github.drompincen.javaclawv1.persistence.repository;

import io.github.drompincen.javaclawv1.persistence.document.LinkDocument;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface LinkRepository extends MongoRepository<LinkDocument, String> {
    List<LinkDocument> findByProjectId(String projectId);
    List<LinkDocument> findByProjectIdAndCategory(String projectId, String category);
    List<LinkDocument> findByProjectIdAndPinnedTrue(String projectId);
    List<LinkDocument> findByBundleId(String bundleId);
}
