package io.github.drompincen.javaclawv1.runtime.lock;

import io.github.drompincen.javaclawv1.persistence.document.LockDocument;
import io.github.drompincen.javaclawv1.persistence.repository.LockRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

@Service
public class SessionLockService {

    private static final long TTL_SECONDS = 60;

    private final LockRepository lockRepository;

    public SessionLockService(LockRepository lockRepository) {
        this.lockRepository = lockRepository;
    }

    public Optional<String> tryAcquire(String sessionId) {
        var existing = lockRepository.findBySessionId(sessionId);
        if (existing.isPresent() && existing.get().getExpiresAt().isAfter(Instant.now())) {
            return Optional.empty();
        }
        existing.ifPresent(lockRepository::delete);

        String owner = UUID.randomUUID().toString();
        LockDocument lock = new LockDocument();
        lock.setLockId(UUID.randomUUID().toString());
        lock.setSessionId(sessionId);
        lock.setOwner(owner);
        lock.setAcquiredAt(Instant.now());
        lock.setExpiresAt(Instant.now().plus(TTL_SECONDS, ChronoUnit.SECONDS));
        lockRepository.save(lock);
        return Optional.of(owner);
    }

    public boolean renew(String sessionId, String owner) {
        return lockRepository.findBySessionId(sessionId)
                .filter(l -> l.getOwner().equals(owner))
                .map(l -> {
                    l.setExpiresAt(Instant.now().plus(TTL_SECONDS, ChronoUnit.SECONDS));
                    lockRepository.save(l);
                    return true;
                })
                .orElse(false);
    }

    public void release(String sessionId, String owner) {
        lockRepository.findBySessionId(sessionId)
                .filter(l -> l.getOwner().equals(owner))
                .ifPresent(lockRepository::delete);
    }

    public boolean isLocked(String sessionId) {
        return lockRepository.findBySessionId(sessionId)
                .filter(l -> l.getExpiresAt().isAfter(Instant.now()))
                .isPresent();
    }
}
