package io.github.drompincen.javaclawv1.persistence.document;

import io.github.drompincen.javaclawv1.protocol.api.TicketDto;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TicketDocumentTest {

    @Test
    void ticketFieldsWork() {
        TicketDocument doc = new TicketDocument();
        Instant now = Instant.now();

        doc.setTicketId("t1");
        doc.setProjectId("p1");
        doc.setTitle("Fix bug");
        doc.setDescription("There is a bug");
        doc.setStatus(TicketDto.TicketStatus.TODO);
        doc.setPriority(TicketDto.TicketPriority.HIGH);
        doc.setLinkedThreadIds(List.of("th1", "th2"));
        doc.setBlockedBy(List.of("t0"));
        doc.setCreatedAt(now);

        assertThat(doc.getTicketId()).isEqualTo("t1");
        assertThat(doc.getStatus()).isEqualTo(TicketDto.TicketStatus.TODO);
        assertThat(doc.getPriority()).isEqualTo(TicketDto.TicketPriority.HIGH);
        assertThat(doc.getLinkedThreadIds()).hasSize(2);
        assertThat(doc.getBlockedBy()).containsExactly("t0");
    }
}
