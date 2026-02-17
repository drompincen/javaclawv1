package io.github.drompincen.javaclawv1.persistence.repository;

import io.github.drompincen.javaclawv1.persistence.document.ThreadDocument;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

public interface ThreadRepository extends MongoRepository<ThreadDocument, String> {
    List<ThreadDocument> findByProjectIdsOrderByUpdatedAtDesc(String projectId);
    Optional<ThreadDocument> findByTitleIgnoreCaseAndProjectIdsContaining(String title, String projectId);
}
