package io.github.drompincen.javaclawv1.persistence.repository;

import io.github.drompincen.javaclawv1.persistence.document.IdeaDocument;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface IdeaRepository extends MongoRepository<IdeaDocument, String> {
    List<IdeaDocument> findByProjectId(String projectId);
    List<IdeaDocument> findByProjectIdAndTagsContaining(String projectId, String tag);
}
