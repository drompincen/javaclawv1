package io.github.drompincen.javaclawv1.persistence.repository;

import io.github.drompincen.javaclawv1.persistence.document.MemoryDocument;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;

import java.util.List;
import java.util.Optional;

public interface MemoryRepository extends MongoRepository<MemoryDocument, String> {

    List<MemoryDocument> findByScopeAndProjectId(MemoryDocument.MemoryScope scope, String projectId);

    List<MemoryDocument> findByScopeAndSessionId(MemoryDocument.MemoryScope scope, String sessionId);

    List<MemoryDocument> findByScope(MemoryDocument.MemoryScope scope);

    Optional<MemoryDocument> findByScopeAndProjectIdAndKey(MemoryDocument.MemoryScope scope, String projectId, String key);

    Optional<MemoryDocument> findByScopeAndSessionIdAndKey(MemoryDocument.MemoryScope scope, String sessionId, String key);

    Optional<MemoryDocument> findByScopeAndKey(MemoryDocument.MemoryScope scope, String key);

    List<MemoryDocument> findByScopeAndThreadId(MemoryDocument.MemoryScope scope, String threadId);

    Optional<MemoryDocument> findByScopeAndThreadIdAndKey(MemoryDocument.MemoryScope scope, String threadId, String key);

    @Query("{'tags': { $in: ?0 }}")
    List<MemoryDocument> findByTagsIn(List<String> tags);

    @Query("{'content': { $regex: ?0, $options: 'i' }}")
    List<MemoryDocument> searchContent(String query);

    @Query("{'$or': [{'scope': 'GLOBAL'}, {'scope': 'PROJECT', 'projectId': ?0}]}")
    List<MemoryDocument> findRelevantMemories(String projectId);

    void deleteByProjectId(String projectId);
}
