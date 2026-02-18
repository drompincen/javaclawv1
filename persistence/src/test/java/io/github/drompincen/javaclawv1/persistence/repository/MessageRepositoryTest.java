package io.github.drompincen.javaclawv1.persistence.repository;

import io.github.drompincen.javaclawv1.persistence.AbstractMongoIntegrationTest;
import io.github.drompincen.javaclawv1.persistence.document.MessageDocument;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MessageRepositoryTest extends AbstractMongoIntegrationTest {

    @Autowired
    private MessageRepository messageRepository;

    @Test
    void findBySessionIdOrderBySeqAsc() {
        messageRepository.save(createMessage("m2", "s1", 2, "assistant", "Response"));
        messageRepository.save(createMessage("m1", "s1", 1, "user", "Hello"));
        messageRepository.save(createMessage("m3", "s2", 1, "user", "Other session"));

        List<MessageDocument> messages = messageRepository.findBySessionIdOrderBySeqAsc("s1");
        assertThat(messages).hasSize(2);
        assertThat(messages.get(0).getSeq()).isEqualTo(1);
        assertThat(messages.get(1).getSeq()).isEqualTo(2);
    }

    @Test
    void countBySessionId() {
        messageRepository.save(createMessage("m1", "s1", 1, "user", "Hello"));
        messageRepository.save(createMessage("m2", "s1", 2, "assistant", "Hi"));
        messageRepository.save(createMessage("m3", "s2", 1, "user", "Other"));

        assertThat(messageRepository.countBySessionId("s1")).isEqualTo(2);
        assertThat(messageRepository.countBySessionId("s2")).isEqualTo(1);
        assertThat(messageRepository.countBySessionId("s3")).isEqualTo(0);
    }

    @Test
    void duplicateSessionSeqRejected() {
        messageRepository.save(createMessage("m1", "s1", 1, "user", "First"));

        MessageDocument duplicate = createMessage("m2", "s1", 1, "user", "Duplicate");
        assertThatThrownBy(() -> messageRepository.save(duplicate))
                .isInstanceOf(DuplicateKeyException.class);
    }

    private MessageDocument createMessage(String id, String sessionId, long seq, String role, String content) {
        MessageDocument doc = new MessageDocument();
        doc.setMessageId(id);
        doc.setSessionId(sessionId);
        doc.setSeq(seq);
        doc.setRole(role);
        doc.setContent(content);
        doc.setTimestamp(Instant.now());
        return doc;
    }
}
