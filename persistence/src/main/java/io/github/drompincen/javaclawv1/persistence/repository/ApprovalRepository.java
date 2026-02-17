package io.github.drompincen.javaclawv1.persistence.repository;

import io.github.drompincen.javaclawv1.persistence.document.ApprovalDocument;
import io.github.drompincen.javaclawv1.protocol.api.ApprovalRequestDto;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface ApprovalRepository extends MongoRepository<ApprovalDocument, String> {
    List<ApprovalDocument> findByThreadIdAndStatus(String threadId, ApprovalRequestDto.ApprovalStatus status);
}
