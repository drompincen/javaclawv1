package io.github.drompincen.javaclawv1.persistence.repository;

import io.github.drompincen.javaclawv1.persistence.AbstractMongoIntegrationTest;
import io.github.drompincen.javaclawv1.persistence.document.TestPromptDocument;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TestPromptRepositoryTest extends AbstractMongoIntegrationTest {

    @Autowired
    private TestPromptRepository testPromptRepository;

    @Test
    void saveAndFindById() {
        TestPromptDocument doc = createPrompt("p1", "controller", "session-1", "test prompt");
        testPromptRepository.save(doc);

        Optional<TestPromptDocument> found = testPromptRepository.findById("p1");
        assertThat(found).isPresent();
        assertThat(found.get().getAgentId()).isEqualTo("controller");
        assertThat(found.get().getPrompt()).isEqualTo("test prompt");
    }

    @Test
    void countBySessionId() {
        testPromptRepository.save(createPrompt("p1", "controller", "s1", "prompt1"));
        testPromptRepository.save(createPrompt("p2", "coder", "s1", "prompt2"));
        testPromptRepository.save(createPrompt("p3", "pm", "s2", "prompt3"));

        assertThat(testPromptRepository.countBySessionId("s1")).isEqualTo(2);
        assertThat(testPromptRepository.countBySessionId("s2")).isEqualTo(1);
        assertThat(testPromptRepository.countBySessionId("s3")).isEqualTo(0);
    }

    @Test
    void findAllByOrderByCreateTimestampAsc() {
        Instant now = Instant.now();
        testPromptRepository.save(createPromptAt("p2", "coder", "s1", "second", now.plusSeconds(1)));
        testPromptRepository.save(createPromptAt("p1", "controller", "s1", "first", now));
        testPromptRepository.save(createPromptAt("p3", "pm", "s1", "third", now.plusSeconds(2)));

        List<TestPromptDocument> sorted = testPromptRepository.findAllByOrderByCreateTimestampAsc();
        assertThat(sorted).hasSize(3);
        assertThat(sorted.get(0).getId()).isEqualTo("p1");
        assertThat(sorted.get(1).getId()).isEqualTo("p2");
        assertThat(sorted.get(2).getId()).isEqualTo("p3");
    }

    @Test
    void responseFallbackFieldIsPersisted() {
        TestPromptDocument doc = createPrompt("p1", "controller", "s1", "prompt");
        doc.setResponseFallback("fallback response text");
        doc.setUserQuery("what is the sprint status?");
        testPromptRepository.save(doc);

        TestPromptDocument found = testPromptRepository.findById("p1").orElseThrow();
        assertThat(found.getResponseFallback()).isEqualTo("fallback response text");
        assertThat(found.getUserQuery()).isEqualTo("what is the sprint status?");
    }

    @Test
    void llmResponseUpdateWorks() {
        TestPromptDocument doc = createPrompt("p1", "controller", "s1", "prompt");
        testPromptRepository.save(doc);

        assertThat(testPromptRepository.findById("p1").get().getLlmResponse()).isNull();

        doc.setLlmResponse("the response from LLM");
        doc.setDuration(42L);
        doc.setResponseTimestamp(Instant.now());
        testPromptRepository.save(doc);

        TestPromptDocument updated = testPromptRepository.findById("p1").orElseThrow();
        assertThat(updated.getLlmResponse()).isEqualTo("the response from LLM");
        assertThat(updated.getDuration()).isEqualTo(42L);
        assertThat(updated.getResponseTimestamp()).isNotNull();
    }

    private TestPromptDocument createPrompt(String id, String agentId, String sessionId, String prompt) {
        return createPromptAt(id, agentId, sessionId, prompt, Instant.now());
    }

    private TestPromptDocument createPromptAt(String id, String agentId, String sessionId, String prompt, Instant ts) {
        TestPromptDocument doc = new TestPromptDocument();
        doc.setId(id);
        doc.setAgentId(agentId);
        doc.setSessionId(sessionId);
        doc.setPrompt(prompt);
        doc.setCreateTimestamp(ts);
        return doc;
    }
}
