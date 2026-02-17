package io.github.drompincen.javaclawv1.runtime.lock;

import io.github.drompincen.javaclawv1.persistence.document.LockDocument;
import io.github.drompincen.javaclawv1.persistence.repository.LockRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SessionLockServiceTest {

    @Mock
    private LockRepository lockRepository;

    private SessionLockService lockService;

    @BeforeEach
    void setUp() {
        lockService = new SessionLockService(lockRepository);
    }

    @Test
    void tryAcquireSucceedsWhenNoLockExists() {
        when(lockRepository.findBySessionId("s1")).thenReturn(Optional.empty());
        when(lockRepository.save(any(LockDocument.class))).thenAnswer(inv -> inv.getArgument(0));

        Optional<String> owner = lockService.tryAcquire("s1");

        assertThat(owner).isPresent();
        verify(lockRepository).save(any(LockDocument.class));
    }

    @Test
    void tryAcquireFailsWhenActiveLockExists() {
        LockDocument existing = new LockDocument();
        existing.setSessionId("s1");
        existing.setOwner("other-owner");
        existing.setExpiresAt(Instant.now().plusSeconds(30));
        when(lockRepository.findBySessionId("s1")).thenReturn(Optional.of(existing));

        Optional<String> owner = lockService.tryAcquire("s1");

        assertThat(owner).isEmpty();
    }

    @Test
    void tryAcquireSucceedsWhenLockIsExpired() {
        LockDocument expired = new LockDocument();
        expired.setSessionId("s1");
        expired.setOwner("old-owner");
        expired.setExpiresAt(Instant.now().minusSeconds(10));
        when(lockRepository.findBySessionId("s1")).thenReturn(Optional.of(expired));
        when(lockRepository.save(any(LockDocument.class))).thenAnswer(inv -> inv.getArgument(0));

        Optional<String> owner = lockService.tryAcquire("s1");

        assertThat(owner).isPresent();
        verify(lockRepository).delete(expired);
    }

    @Test
    void releaseDeletesLockForCorrectOwner() {
        LockDocument lock = new LockDocument();
        lock.setSessionId("s1");
        lock.setOwner("my-owner");
        when(lockRepository.findBySessionId("s1")).thenReturn(Optional.of(lock));

        lockService.release("s1", "my-owner");

        verify(lockRepository).delete(lock);
    }

    @Test
    void releaseDoesNothingForWrongOwner() {
        LockDocument lock = new LockDocument();
        lock.setSessionId("s1");
        lock.setOwner("other-owner");
        when(lockRepository.findBySessionId("s1")).thenReturn(Optional.of(lock));

        lockService.release("s1", "wrong-owner");

        verify(lockRepository, never()).delete(any(LockDocument.class));
    }

    @Test
    void isLockedReturnsTrueForActiveLock() {
        LockDocument lock = new LockDocument();
        lock.setExpiresAt(Instant.now().plusSeconds(30));
        when(lockRepository.findBySessionId("s1")).thenReturn(Optional.of(lock));

        assertThat(lockService.isLocked("s1")).isTrue();
    }

    @Test
    void isLockedReturnsFalseWhenNoLock() {
        when(lockRepository.findBySessionId("s1")).thenReturn(Optional.empty());

        assertThat(lockService.isLocked("s1")).isFalse();
    }
}
