package io.github.drompincen.javaclawv1.persistence.document;

import io.github.drompincen.javaclawv1.protocol.api.ModelConfig;
import io.github.drompincen.javaclawv1.protocol.api.SessionStatus;
import io.github.drompincen.javaclawv1.protocol.api.ToolPolicy;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SessionDocumentTest {

    @Test
    void gettersAndSettersWork() {
        SessionDocument doc = new SessionDocument();
        Instant now = Instant.now();

        doc.setSessionId("s1");
        doc.setCreatedAt(now);
        doc.setUpdatedAt(now);
        doc.setStatus(SessionStatus.IDLE);
        doc.setModelConfig(ModelConfig.defaults());
        doc.setToolPolicy(ToolPolicy.allowAll());
        doc.setCurrentCheckpointId("cp1");
        doc.setMetadata(Map.of("key", "value"));

        assertThat(doc.getSessionId()).isEqualTo("s1");
        assertThat(doc.getCreatedAt()).isEqualTo(now);
        assertThat(doc.getStatus()).isEqualTo(SessionStatus.IDLE);
        assertThat(doc.getModelConfig()).isNotNull();
        assertThat(doc.getToolPolicy()).isNotNull();
        assertThat(doc.getCurrentCheckpointId()).isEqualTo("cp1");
        assertThat(doc.getMetadata()).containsEntry("key", "value");
    }
}
