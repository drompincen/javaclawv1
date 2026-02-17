package io.github.drompincen.javaclawv1.runtime.agent.llm;

import io.github.drompincen.javaclawv1.runtime.agent.graph.AgentState;
import reactor.core.publisher.Flux;

public interface LlmService {

    Flux<String> streamResponse(AgentState state);

    String blockingResponse(AgentState state);
}
