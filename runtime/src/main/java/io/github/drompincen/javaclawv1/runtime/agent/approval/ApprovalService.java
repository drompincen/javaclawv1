package io.github.drompincen.javaclawv1.runtime.agent.approval;

import io.github.drompincen.javaclawv1.persistence.document.ApprovalDocument;
import io.github.drompincen.javaclawv1.persistence.repository.ApprovalRepository;
import io.github.drompincen.javaclawv1.persistence.stream.ChangeStreamService;
import io.github.drompincen.javaclawv1.protocol.api.ApprovalRequestDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Service
public class ApprovalService {

    private static final Logger log = LoggerFactory.getLogger(ApprovalService.class);

    private final ApprovalRepository approvalRepository;
    private final ChangeStreamService changeStreamService;

    public ApprovalService(ApprovalRepository approvalRepository, ChangeStreamService changeStreamService) {
        this.approvalRepository = approvalRepository;
        this.changeStreamService = changeStreamService;
    }

    public String createRequest(String threadId, String toolName, Object input) {
        ApprovalDocument doc = new ApprovalDocument();
        doc.setApprovalId(UUID.randomUUID().toString());
        doc.setThreadId(threadId);
        doc.setToolName(toolName);
        doc.setToolInput(input);
        doc.setStatus(ApprovalRequestDto.ApprovalStatus.PENDING);
        doc.setCreatedAt(Instant.now());
        approvalRepository.save(doc);
        log.info("Created approval request {} for tool {} in thread {}", doc.getApprovalId(), toolName, threadId);
        return doc.getApprovalId();
    }

    public Optional<ApprovalRequestDto.ApprovalStatus> waitForResponse(String approvalId, Duration timeout) {
        // Poll for response (change stream watch would be ideal but for blocking context, poll)
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        while (System.currentTimeMillis() < deadline) {
            Optional<ApprovalDocument> doc = approvalRepository.findById(approvalId);
            if (doc.isPresent() && doc.get().getStatus() != ApprovalRequestDto.ApprovalStatus.PENDING) {
                return Optional.of(doc.get().getStatus());
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Optional.empty();
            }
        }
        log.warn("Approval {} timed out after {}", approvalId, timeout);
        return Optional.empty();
    }

    public void respond(String approvalId, ApprovalRequestDto.ApprovalStatus status) {
        approvalRepository.findById(approvalId).ifPresent(doc -> {
            doc.setStatus(status);
            doc.setRespondedAt(Instant.now());
            approvalRepository.save(doc);
            log.info("Approval {} responded with {}", approvalId, status);
        });
    }
}
