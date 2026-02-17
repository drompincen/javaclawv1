package io.github.drompincen.javaclawv1.persistence.document;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class LockDocumentTest {

    @Test
    void lockFieldsPreserved() {
        LockDocument doc = new LockDocument();
        Instant now = Instant.now();
        Instant expires = now.plusSeconds(60);

        doc.setLockId("l1");
        doc.setSessionId("s1");
        doc.setOwner("owner-uuid");
        doc.setAcquiredAt(now);
        doc.setExpiresAt(expires);

        assertThat(doc.getLockId()).isEqualTo("l1");
        assertThat(doc.getSessionId()).isEqualTo("s1");
        assertThat(doc.getOwner()).isEqualTo("owner-uuid");
        assertThat(doc.getExpiresAt()).isAfter(doc.getAcquiredAt());
    }
}
