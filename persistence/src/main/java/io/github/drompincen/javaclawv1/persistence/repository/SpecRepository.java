package io.github.drompincen.javaclawv1.persistence.repository;

import io.github.drompincen.javaclawv1.persistence.document.SpecDocument;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;

import java.util.List;

public interface SpecRepository extends MongoRepository<SpecDocument, String> {
    List<SpecDocument> findByTagsContaining(String tag);
    @Query("{ '$text': { '$search': ?0 } }")
    List<SpecDocument> searchByText(String query);
    List<SpecDocument> findByTagsContainingAndTitleContainingIgnoreCase(String tag, String q);
}
