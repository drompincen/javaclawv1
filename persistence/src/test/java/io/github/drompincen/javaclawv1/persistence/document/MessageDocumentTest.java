package io.github.drompincen.javaclawv1.persistence.document;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class MessageDocumentTest {

    @Test
    void messageFieldsPreserved() {
        MessageDocument doc = new MessageDocument();
        doc.setMessageId("m1");
        doc.setSessionId("s1");
        doc.setSeq(1);
        doc.setRole("user");
        doc.setContent("Hello agent");
        doc.setTimestamp(Instant.now());

        assertThat(doc.getMessageId()).isEqualTo("m1");
        assertThat(doc.getRole()).isEqualTo("user");
        assertThat(doc.getContent()).isEqualTo("Hello agent");
        assertThat(doc.getSeq()).isEqualTo(1);
    }
}
