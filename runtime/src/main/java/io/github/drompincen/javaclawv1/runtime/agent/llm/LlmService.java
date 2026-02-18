package io.github.drompincen.javaclawv1.runtime.agent.llm;

import io.github.drompincen.javaclawv1.runtime.agent.graph.AgentState;
import reactor.core.publisher.Flux;

public interface LlmService {

    Flux<String> streamResponse(AgentState state);

    String blockingResponse(AgentState state);

    /**
     * Returns true if this LLM service has a working provider configured.
     * Used to short-circuit the multi-agent loop when no API key is available.
     */
    default boolean isAvailable() { return true; }
}
