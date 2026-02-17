package io.github.drompincen.javaclawv1.protocol.event;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EventTypeTest {

    @Test
    void coreEventTypesExist() {
        assertThat(EventType.valueOf("USER_MESSAGE_RECEIVED")).isNotNull();
        assertThat(EventType.valueOf("AGENT_STEP_STARTED")).isNotNull();
        assertThat(EventType.valueOf("MODEL_TOKEN_DELTA")).isNotNull();
        assertThat(EventType.valueOf("TOOL_CALL_PROPOSED")).isNotNull();
        assertThat(EventType.valueOf("TOOL_RESULT")).isNotNull();
        assertThat(EventType.valueOf("SESSION_STATUS_CHANGED")).isNotNull();
        assertThat(EventType.valueOf("ERROR")).isNotNull();
    }

    @Test
    void projectEventTypesExist() {
        assertThat(EventType.valueOf("TICKET_CREATED")).isNotNull();
        assertThat(EventType.valueOf("APPROVAL_REQUESTED")).isNotNull();
        assertThat(EventType.valueOf("RESOURCE_ASSIGNED")).isNotNull();
    }
}
