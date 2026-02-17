package io.github.drompincen.javaclawv1.persistence.document;

import io.github.drompincen.javaclawv1.protocol.event.EventType;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class EventDocumentTest {

    @Test
    void fieldsAreSetCorrectly() {
        EventDocument doc = new EventDocument();
        Instant now = Instant.now();

        doc.setEventId("e1");
        doc.setSessionId("s1");
        doc.setSeq(42);
        doc.setType(EventType.MODEL_TOKEN_DELTA);
        doc.setPayload("hello");
        doc.setTimestamp(now);

        assertThat(doc.getEventId()).isEqualTo("e1");
        assertThat(doc.getSessionId()).isEqualTo("s1");
        assertThat(doc.getSeq()).isEqualTo(42);
        assertThat(doc.getType()).isEqualTo(EventType.MODEL_TOKEN_DELTA);
        assertThat(doc.getPayload()).isEqualTo("hello");
        assertThat(doc.getTimestamp()).isEqualTo(now);
    }
}
