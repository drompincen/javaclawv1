package io.github.drompincen.javaclawv1.persistence.repository;

import io.github.drompincen.javaclawv1.persistence.AbstractMongoIntegrationTest;
import io.github.drompincen.javaclawv1.persistence.document.MemoryDocument;
import io.github.drompincen.javaclawv1.persistence.document.MemoryDocument.MemoryScope;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MemoryRepositoryTest extends AbstractMongoIntegrationTest {

    @Autowired
    private MemoryRepository memoryRepository;

    @Test
    void findByScopeAndProjectId() {
        memoryRepository.save(createMemory("m1", MemoryScope.PROJECT, "proj1", null, "key1", "content1"));
        memoryRepository.save(createMemory("m2", MemoryScope.PROJECT, "proj1", null, "key2", "content2"));
        memoryRepository.save(createMemory("m3", MemoryScope.PROJECT, "proj2", null, "key1", "other"));

        List<MemoryDocument> result = memoryRepository.findByScopeAndProjectId(MemoryScope.PROJECT, "proj1");
        assertThat(result).hasSize(2);
    }

    @Test
    void findByTagsIn() {
        MemoryDocument m1 = createMemory("m1", MemoryScope.GLOBAL, null, null, "k1", "tagged");
        m1.setTags(List.of("java", "spring"));
        memoryRepository.save(m1);

        MemoryDocument m2 = createMemory("m2", MemoryScope.GLOBAL, null, null, "k2", "also tagged");
        m2.setTags(List.of("python"));
        memoryRepository.save(m2);

        List<MemoryDocument> result = memoryRepository.findByTagsIn(List.of("java"));
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getMemoryId()).isEqualTo("m1");
    }

    @Test
    void searchContent() {
        memoryRepository.save(createMemory("m1", MemoryScope.GLOBAL, null, null, "k1",
                "The sprint retrospective went well"));
        memoryRepository.save(createMemory("m2", MemoryScope.GLOBAL, null, null, "k2",
                "Database migration complete"));

        List<MemoryDocument> result = memoryRepository.searchContent("sprint");
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getContent()).contains("sprint");
    }

    @Test
    void findRelevantMemories() {
        memoryRepository.save(createMemory("m1", MemoryScope.GLOBAL, null, null, "k1", "Global memory"));
        memoryRepository.save(createMemory("m2", MemoryScope.PROJECT, "proj1", null, "k2", "Project memory"));
        memoryRepository.save(createMemory("m3", MemoryScope.PROJECT, "proj2", null, "k3", "Other project"));
        memoryRepository.save(createMemory("m4", MemoryScope.SESSION, null, null, "k4", "Session memory"));

        List<MemoryDocument> result = memoryRepository.findRelevantMemories("proj1");
        assertThat(result).hasSize(2);
    }

    private MemoryDocument createMemory(String id, MemoryScope scope, String projectId,
                                         String threadId, String key, String content) {
        MemoryDocument doc = new MemoryDocument();
        doc.setMemoryId(id);
        doc.setScope(scope);
        doc.setProjectId(projectId);
        doc.setThreadId(threadId);
        doc.setKey(key);
        doc.setContent(content);
        doc.setCreatedAt(Instant.now());
        doc.setUpdatedAt(Instant.now());
        return doc;
    }
}
