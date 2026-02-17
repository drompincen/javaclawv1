package io.github.drompincen.javaclawv1.protocol.api;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SessionStatusTest {

    @Test
    void allStatusValuesExist() {
        assertThat(SessionStatus.values()).containsExactly(
                SessionStatus.IDLE,
                SessionStatus.RUNNING,
                SessionStatus.PAUSED,
                SessionStatus.FAILED,
                SessionStatus.COMPLETED);
    }

    @Test
    void valueOfReturnsCorrectEnum() {
        assertThat(SessionStatus.valueOf("RUNNING")).isEqualTo(SessionStatus.RUNNING);
    }
}
