package io.github.drompincen.javaclawv1.protocol.api;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TicketDtoTest {

    @Test
    void ticketStatusValues() {
        assertThat(TicketDto.TicketStatus.values()).containsExactly(
                TicketDto.TicketStatus.TODO,
                TicketDto.TicketStatus.IN_PROGRESS,
                TicketDto.TicketStatus.REVIEW,
                TicketDto.TicketStatus.DONE,
                TicketDto.TicketStatus.BLOCKED);
    }

    @Test
    void ticketPriorityValues() {
        assertThat(TicketDto.TicketPriority.values()).containsExactly(
                TicketDto.TicketPriority.LOW,
                TicketDto.TicketPriority.MEDIUM,
                TicketDto.TicketPriority.HIGH,
                TicketDto.TicketPriority.CRITICAL);
    }
}
