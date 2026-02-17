package io.github.drompincen.javaclawv1.persistence.document;

import io.github.drompincen.javaclawv1.protocol.api.ApprovalRequestDto;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@Document(collection = "approvals")
@CompoundIndex(name = "thread_status", def = "{'threadId': 1, 'status': 1}")
public class ApprovalDocument {

    @Id
    private String approvalId;
    private String threadId;
    private String toolName;
    private Object toolInput;
    private ApprovalRequestDto.ApprovalStatus status;
    private Instant createdAt;
    private Instant respondedAt;

    public ApprovalDocument() {}

    public String getApprovalId() { return approvalId; }
    public void setApprovalId(String approvalId) { this.approvalId = approvalId; }

    public String getThreadId() { return threadId; }
    public void setThreadId(String threadId) { this.threadId = threadId; }

    public String getToolName() { return toolName; }
    public void setToolName(String toolName) { this.toolName = toolName; }

    public Object getToolInput() { return toolInput; }
    public void setToolInput(Object toolInput) { this.toolInput = toolInput; }

    public ApprovalRequestDto.ApprovalStatus getStatus() { return status; }
    public void setStatus(ApprovalRequestDto.ApprovalStatus status) { this.status = status; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getRespondedAt() { return respondedAt; }
    public void setRespondedAt(Instant respondedAt) { this.respondedAt = respondedAt; }
}
