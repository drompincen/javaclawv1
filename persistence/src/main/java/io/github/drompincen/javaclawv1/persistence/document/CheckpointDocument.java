package io.github.drompincen.javaclawv1.persistence.document;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@Document(collection = "checkpoints")
@CompoundIndex(name = "session_step", def = "{'sessionId': 1, 'stepNo': -1}")
public class CheckpointDocument {

    @Id
    private String checkpointId;
    private String sessionId;
    private int stepNo;
    private Instant createdAt;
    private Object state;
    private long eventOffset;

    public CheckpointDocument() {}

    public String getCheckpointId() { return checkpointId; }
    public void setCheckpointId(String checkpointId) { this.checkpointId = checkpointId; }

    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }

    public int getStepNo() { return stepNo; }
    public void setStepNo(int stepNo) { this.stepNo = stepNo; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Object getState() { return state; }
    public void setState(Object state) { this.state = state; }

    public long getEventOffset() { return eventOffset; }
    public void setEventOffset(long eventOffset) { this.eventOffset = eventOffset; }
}
