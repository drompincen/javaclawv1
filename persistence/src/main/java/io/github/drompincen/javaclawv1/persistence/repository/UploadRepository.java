package io.github.drompincen.javaclawv1.persistence.repository;

import io.github.drompincen.javaclawv1.persistence.document.UploadDocument;
import io.github.drompincen.javaclawv1.protocol.api.UploadStatus;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface UploadRepository extends MongoRepository<UploadDocument, String> {
    List<UploadDocument> findByProjectId(String projectId);
    List<UploadDocument> findByProjectIdAndStatus(String projectId, UploadStatus status);
    List<UploadDocument> findByThreadId(String threadId);
}
