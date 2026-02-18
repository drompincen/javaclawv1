package io.github.drompincen.javaclawv1.persistence.document;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@Document(collection = "testPrompts")
public class TestPromptDocument {

    @Id
    private String id;

    private String prompt;

    @Indexed
    private String agentId;

    @Indexed
    private String sessionId;

    private String llmResponse;

    private Long duration;

    private Instant createTimestamp;

    private Instant responseTimestamp;

    public TestPromptDocument() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getPrompt() { return prompt; }
    public void setPrompt(String prompt) { this.prompt = prompt; }

    public String getAgentId() { return agentId; }
    public void setAgentId(String agentId) { this.agentId = agentId; }

    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }

    public String getLlmResponse() { return llmResponse; }
    public void setLlmResponse(String llmResponse) { this.llmResponse = llmResponse; }

    public Long getDuration() { return duration; }
    public void setDuration(Long duration) { this.duration = duration; }

    public Instant getCreateTimestamp() { return createTimestamp; }
    public void setCreateTimestamp(Instant createTimestamp) { this.createTimestamp = createTimestamp; }

    public Instant getResponseTimestamp() { return responseTimestamp; }
    public void setResponseTimestamp(Instant responseTimestamp) { this.responseTimestamp = responseTimestamp; }

    private String userQuery;

    public String getUserQuery() { return userQuery; }
    public void setUserQuery(String userQuery) { this.userQuery = userQuery; }

    private String responseFallback;

    public String getResponseFallback() { return responseFallback; }
    public void setResponseFallback(String responseFallback) { this.responseFallback = responseFallback; }
}
