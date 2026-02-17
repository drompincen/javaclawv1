package io.github.drompincen.javaclawv1.runtime.agent;

import io.github.drompincen.javaclawv1.persistence.document.EventDocument;
import io.github.drompincen.javaclawv1.persistence.repository.EventRepository;
import io.github.drompincen.javaclawv1.protocol.event.EventType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EventServiceTest {

    @Mock
    private EventRepository eventRepository;

    private EventService eventService;

    @BeforeEach
    void setUp() {
        eventService = new EventService(eventRepository);
    }

    @Test
    void emitCreatesEventWithIncrementingSeq() {
        when(eventRepository.findTopBySessionIdOrderBySeqDesc("s1"))
                .thenReturn(Optional.empty());
        when(eventRepository.save(any(EventDocument.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        EventDocument event = eventService.emit("s1", EventType.USER_MESSAGE_RECEIVED, "hello");

        assertThat(event.getSessionId()).isEqualTo("s1");
        assertThat(event.getType()).isEqualTo(EventType.USER_MESSAGE_RECEIVED);
        assertThat(event.getPayload()).isEqualTo("hello");
        assertThat(event.getEventId()).isNotNull();
        assertThat(event.getTimestamp()).isNotNull();
        assertThat(event.getSeq()).isGreaterThanOrEqualTo(1);
        verify(eventRepository).save(any(EventDocument.class));
    }

    @Test
    void emitWithoutPayloadSetsNullPayload() {
        when(eventRepository.findTopBySessionIdOrderBySeqDesc("s1"))
                .thenReturn(Optional.empty());
        when(eventRepository.save(any(EventDocument.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        EventDocument event = eventService.emit("s1", EventType.AGENT_STEP_STARTED);

        assertThat(event.getType()).isEqualTo(EventType.AGENT_STEP_STARTED);
    }
}
