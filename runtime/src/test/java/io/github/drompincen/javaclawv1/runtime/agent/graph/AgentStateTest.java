package io.github.drompincen.javaclawv1.runtime.agent.graph;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AgentStateTest {

    @Test
    void newStateHasEmptyCollections() {
        AgentState state = new AgentState();

        assertThat(state.getMessages()).isEmpty();
        assertThat(state.getPendingToolCalls()).isEmpty();
        assertThat(state.getPendingApprovals()).isEmpty();
        assertThat(state.getContext()).isEmpty();
        assertThat(state.getStepNo()).isZero();
    }

    @Test
    void withMessageReturnsNewInstance() {
        AgentState original = new AgentState();
        AgentState withMsg = original.withMessage("user", "Hello");

        assertThat(withMsg).isNotSameAs(original);
        assertThat(withMsg.getMessages()).hasSize(1);
        assertThat(original.getMessages()).isEmpty();
    }

    @Test
    void withMessagePreservesRoleAndContent() {
        AgentState state = new AgentState().withMessage("user", "Hello");

        assertThat(state.getMessages().get(0).get("role")).isEqualTo("user");
        assertThat(state.getMessages().get(0).get("content")).isEqualTo("Hello");
    }

    @Test
    void withStepSetsStepNumber() {
        AgentState state = new AgentState().withStep(5);

        assertThat(state.getStepNo()).isEqualTo(5);
    }

    @Test
    void chainedOperationsPreserveAll() {
        AgentState state = new AgentState()
                .withMessage("user", "Do something")
                .withMessage("assistant", "Done")
                .withStep(2);

        assertThat(state.getMessages()).hasSize(2);
        assertThat(state.getStepNo()).isEqualTo(2);
    }

    @Test
    void threadIdAndProjectIdArePreserved() {
        AgentState state = new AgentState();
        state.setThreadId("t1");
        state.setProjectId("p1");

        AgentState copy = state.withMessage("user", "test");
        assertThat(copy.getThreadId()).isEqualTo("t1");
        assertThat(copy.getProjectId()).isEqualTo("p1");
    }
}
