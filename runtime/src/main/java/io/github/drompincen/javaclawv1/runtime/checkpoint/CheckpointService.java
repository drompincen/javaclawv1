package io.github.drompincen.javaclawv1.runtime.checkpoint;

import io.github.drompincen.javaclawv1.persistence.document.CheckpointDocument;
import io.github.drompincen.javaclawv1.persistence.repository.CheckpointRepository;
import io.github.drompincen.javaclawv1.persistence.repository.EventRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Service
public class CheckpointService {

    private final CheckpointRepository checkpointRepository;
    private final EventRepository eventRepository;

    public CheckpointService(CheckpointRepository checkpointRepository, EventRepository eventRepository) {
        this.checkpointRepository = checkpointRepository;
        this.eventRepository = eventRepository;
    }

    public CheckpointDocument createCheckpoint(String sessionId, int stepNo, Object state) {
        long offset = eventRepository.findTopBySessionIdOrderBySeqDesc(sessionId)
                .map(e -> e.getSeq()).orElse(0L);

        CheckpointDocument cp = new CheckpointDocument();
        cp.setCheckpointId(UUID.randomUUID().toString());
        cp.setSessionId(sessionId);
        cp.setStepNo(stepNo);
        cp.setCreatedAt(Instant.now());
        cp.setState(state);
        cp.setEventOffset(offset);
        return checkpointRepository.save(cp);
    }

    public Optional<CheckpointDocument> getLatestCheckpoint(String sessionId) {
        return checkpointRepository.findTopBySessionIdOrderByStepNoDesc(sessionId);
    }
}
