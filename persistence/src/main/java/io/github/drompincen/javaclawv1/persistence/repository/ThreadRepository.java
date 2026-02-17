package io.github.drompincen.javaclawv1.persistence.repository;

import io.github.drompincen.javaclawv1.persistence.document.ThreadDocument;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface ThreadRepository extends MongoRepository<ThreadDocument, String> {
    List<ThreadDocument> findByProjectIdsOrderByUpdatedAtDesc(String projectId);
}
