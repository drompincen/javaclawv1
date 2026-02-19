package io.github.drompincen.javaclawv1.persistence.repository;

import io.github.drompincen.javaclawv1.persistence.document.ChecklistTemplateDocument;
import io.github.drompincen.javaclawv1.protocol.api.ChecklistCategory;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface ChecklistTemplateRepository extends MongoRepository<ChecklistTemplateDocument, String> {
    List<ChecklistTemplateDocument> findByCategory(ChecklistCategory category);
    List<ChecklistTemplateDocument> findByProjectIdOrProjectIdIsNull(String projectId);
}
