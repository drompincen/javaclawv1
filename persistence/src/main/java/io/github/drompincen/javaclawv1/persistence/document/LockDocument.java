package io.github.drompincen.javaclawv1.persistence.document;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@Document(collection = "locks")
public class LockDocument {

    @Id
    private String lockId;
    private String sessionId;
    private String owner;

    @Indexed(expireAfterSeconds = 60)
    private Instant expiresAt;
    private Instant acquiredAt;

    public LockDocument() {}

    public String getLockId() { return lockId; }
    public void setLockId(String lockId) { this.lockId = lockId; }

    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }

    public String getOwner() { return owner; }
    public void setOwner(String owner) { this.owner = owner; }

    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }

    public Instant getAcquiredAt() { return acquiredAt; }
    public void setAcquiredAt(Instant acquiredAt) { this.acquiredAt = acquiredAt; }
}
