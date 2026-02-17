package io.github.drompincen.javaclawv1.persistence.document;

import io.github.drompincen.javaclawv1.protocol.event.EventType;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@Document(collection = "events")
@CompoundIndex(name = "session_seq", def = "{'sessionId': 1, 'seq': 1}", unique = true)
public class EventDocument {

    @Id
    private String eventId;
    private String sessionId;
    private long seq;
    private EventType type;
    private Object payload;
    private Instant timestamp;

    public EventDocument() {}

    public String getEventId() { return eventId; }
    public void setEventId(String eventId) { this.eventId = eventId; }

    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }

    public long getSeq() { return seq; }
    public void setSeq(long seq) { this.seq = seq; }

    public EventType getType() { return type; }
    public void setType(EventType type) { this.type = type; }

    public Object getPayload() { return payload; }
    public void setPayload(Object payload) { this.payload = payload; }

    public Instant getTimestamp() { return timestamp; }
    public void setTimestamp(Instant timestamp) { this.timestamp = timestamp; }
}
