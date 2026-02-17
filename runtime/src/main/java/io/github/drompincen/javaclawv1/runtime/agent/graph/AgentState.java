package io.github.drompincen.javaclawv1.runtime.agent.graph;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class AgentState {

    private String threadId;
    private String projectId;
    private String currentAgentId;
    private List<Map<String, String>> messages;
    private List<Map<String, Object>> pendingToolCalls;
    private List<String> pendingApprovals;
    private String currentPlan;
    private int stepNo;
    private Map<String, Object> context;

    public AgentState() {
        this.messages = new ArrayList<>();
        this.pendingToolCalls = new ArrayList<>();
        this.pendingApprovals = new ArrayList<>();
        this.context = new HashMap<>();
    }

    public AgentState withMessage(String role, String content) {
        AgentState copy = copy();
        copy.messages.add(Map.of("role", role, "content", content != null ? content : ""));
        return copy;
    }

    /**
     * Add a multimodal message (e.g. with images). The partsJson is a serialized
     * JSON array of ContentPart objects that the LLM service will parse.
     */
    public AgentState withMultimodalMessage(String role, String content, String partsJson) {
        AgentState copy = copy();
        Map<String, String> msg = new java.util.HashMap<>();
        msg.put("role", role);
        msg.put("content", content != null ? content : "");
        msg.put("parts", partsJson);
        copy.messages.add(msg);
        return copy;
    }

    public AgentState withToolResult(String toolName, String result) {
        AgentState copy = copy();
        copy.messages.add(Map.of("role", "tool", "name", toolName, "content", result));
        copy.pendingToolCalls = new ArrayList<>();
        return copy;
    }

    public AgentState withStep(int stepNo) {
        AgentState copy = copy();
        copy.stepNo = stepNo;
        return copy;
    }

    public AgentState withAgent(String agentId) {
        AgentState copy = copy();
        copy.currentAgentId = agentId;
        return copy;
    }

    private AgentState copy() {
        AgentState s = new AgentState();
        s.threadId = this.threadId;
        s.projectId = this.projectId;
        s.currentAgentId = this.currentAgentId;
        s.messages = new ArrayList<>(this.messages);
        s.pendingToolCalls = new ArrayList<>(this.pendingToolCalls);
        s.pendingApprovals = new ArrayList<>(this.pendingApprovals);
        s.currentPlan = this.currentPlan;
        s.stepNo = this.stepNo;
        s.context = new HashMap<>(this.context);
        return s;
    }

    // Getters and setters
    public String getThreadId() { return threadId; }
    public void setThreadId(String threadId) { this.threadId = threadId; }

    public String getProjectId() { return projectId; }
    public void setProjectId(String projectId) { this.projectId = projectId; }

    public String getCurrentAgentId() { return currentAgentId; }
    public void setCurrentAgentId(String currentAgentId) { this.currentAgentId = currentAgentId; }

    public List<Map<String, String>> getMessages() { return messages; }
    public void setMessages(List<Map<String, String>> messages) { this.messages = messages; }

    public List<Map<String, Object>> getPendingToolCalls() { return pendingToolCalls; }
    public void setPendingToolCalls(List<Map<String, Object>> pendingToolCalls) { this.pendingToolCalls = pendingToolCalls; }

    public List<String> getPendingApprovals() { return pendingApprovals; }
    public void setPendingApprovals(List<String> pendingApprovals) { this.pendingApprovals = pendingApprovals; }

    public String getCurrentPlan() { return currentPlan; }
    public void setCurrentPlan(String currentPlan) { this.currentPlan = currentPlan; }

    public int getStepNo() { return stepNo; }
    public void setStepNo(int stepNo) { this.stepNo = stepNo; }

    public Map<String, Object> getContext() { return context; }
    public void setContext(Map<String, Object> context) { this.context = context; }
}
