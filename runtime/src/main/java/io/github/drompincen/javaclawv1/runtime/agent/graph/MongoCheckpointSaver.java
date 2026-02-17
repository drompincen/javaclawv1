package io.github.drompincen.javaclawv1.runtime.agent.graph;

import io.github.drompincen.javaclawv1.persistence.document.CheckpointDocument;
import io.github.drompincen.javaclawv1.persistence.repository.CheckpointRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Component
public class MongoCheckpointSaver {

    private static final Logger log = LoggerFactory.getLogger(MongoCheckpointSaver.class);

    private final CheckpointRepository checkpointRepository;
    private final ObjectMapper objectMapper;

    public MongoCheckpointSaver(CheckpointRepository checkpointRepository, ObjectMapper objectMapper) {
        this.checkpointRepository = checkpointRepository;
        this.objectMapper = objectMapper;
    }

    public void save(String threadId, int stepNo, AgentState state) {
        try {
            CheckpointDocument cp = new CheckpointDocument();
            cp.setCheckpointId(UUID.randomUUID().toString());
            cp.setSessionId(threadId);
            cp.setStepNo(stepNo);
            cp.setCreatedAt(Instant.now());
            // Store as JSON string to avoid Spring Data MongoDB _class metadata issues
            // with Jackson tree nodes (ObjectNode has no no-arg constructor)
            cp.setState(objectMapper.writeValueAsString(state));
            cp.setEventOffset(stepNo);
            checkpointRepository.save(cp);
            log.debug("Saved checkpoint for thread {} at step {}", threadId, stepNo);
        } catch (Exception e) {
            log.error("Failed to save checkpoint for thread {}", threadId, e);
        }
    }

    public Optional<AgentState> load(String threadId) {
        return checkpointRepository.findTopBySessionIdOrderByStepNoDesc(threadId)
                .map(cp -> {
                    try {
                        Object stateObj = cp.getState();
                        if (stateObj instanceof String stateJson) {
                            return objectMapper.readValue(stateJson, AgentState.class);
                        }
                        // Legacy: handle old checkpoints stored as Map/Document
                        return objectMapper.convertValue(stateObj, AgentState.class);
                    } catch (Exception e) {
                        log.error("Failed to load checkpoint for thread {}", threadId, e);
                        return null;
                    }
                });
    }
}
