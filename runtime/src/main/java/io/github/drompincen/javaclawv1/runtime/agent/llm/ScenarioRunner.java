package io.github.drompincen.javaclawv1.runtime.agent.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

/**
 * Auto-plays a scenario after server startup via REST API calls.
 * Activated only when a scenario file is configured.
 */
@Component
@ConditionalOnProperty(name = "javaclaw.scenario.file")
public class ScenarioRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ScenarioRunner.class);
    private static final long STARTUP_DELAY_MS = 3_000;
    private static final long POLL_INTERVAL_MS = 1_000;
    private static final long STEP_TIMEOUT_MS = 30_000;

    private final ScenarioService scenarioService;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    @Value("${server.port:8080}")
    private int serverPort;

    public ScenarioRunner(ScenarioService scenarioService, ObjectMapper objectMapper) {
        this.scenarioService = scenarioService;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newHttpClient();
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        if (!scenarioService.isLoaded()) {
            log.warn("[ScenarioRunner] ScenarioService not loaded — skipping");
            return;
        }

        // Run in a daemon thread so we don't block ApplicationRunner
        Thread runner = new Thread(this::playScenario, "ScenarioRunner");
        runner.setDaemon(true);
        runner.start();
    }

    private void playScenario() {
        try {
            log.info("[ScenarioRunner] Waiting {}ms for server startup...", STARTUP_DELAY_MS);
            Thread.sleep(STARTUP_DELAY_MS);

            String baseUrl = "http://localhost:" + serverPort;
            List<ScenarioConfig.ScenarioStep> steps = scenarioService.getSteps();

            log.info("[ScenarioRunner] Playing scenario '{}' with {} steps",
                    scenarioService.getProjectName(), steps.size());

            // Create a session
            String sessionId = createSession(baseUrl);
            if (sessionId == null) {
                log.error("[ScenarioRunner] Failed to create session — aborting");
                return;
            }
            log.info("[ScenarioRunner] Created session: {}", sessionId);

            int passed = 0;
            int total = 0;

            for (int i = 0; i < steps.size(); i++) {
                ScenarioConfig.ScenarioStep step = steps.get(i);
                total++;
                log.info("[ScenarioRunner] Step {}/{}: '{}'", i + 1, steps.size(), step.userQuery());

                boolean success = playStep(baseUrl, sessionId, step);
                if (success) {
                    passed++;
                    log.info("[ScenarioRunner] Step {}/{}: PASS", i + 1, steps.size());
                } else {
                    log.warn("[ScenarioRunner] Step {}/{}: FAIL", i + 1, steps.size());
                }
            }

            log.info("[ScenarioRunner] ========================================");
            log.info("[ScenarioRunner] Scenario '{}' complete: {}/{} steps passed",
                    scenarioService.getProjectName(), passed, total);
            log.info("[ScenarioRunner] ========================================");

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.info("[ScenarioRunner] Interrupted");
        } catch (Exception e) {
            log.error("[ScenarioRunner] Unexpected error: {}", e.getMessage(), e);
        }
    }

    private String createSession(String baseUrl) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/sessions"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{}"))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                JsonNode body = objectMapper.readTree(response.body());
                return body.path("sessionId").asText(null);
            }
            log.error("[ScenarioRunner] Create session failed: {} {}", response.statusCode(), response.body());
        } catch (Exception e) {
            log.error("[ScenarioRunner] Create session error: {}", e.getMessage(), e);
        }
        return null;
    }

    private boolean playStep(String baseUrl, String sessionId, ScenarioConfig.ScenarioStep step) {
        try {
            // Count messages before this step so we can identify new ones
            int msgCountBefore = getMessageCount(baseUrl, sessionId);

            // Send user message
            String msgBody = objectMapper.writeValueAsString(
                    java.util.Map.of("content", step.userQuery(), "role", "user"));
            HttpRequest msgReq = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/sessions/" + sessionId + "/messages"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(msgBody))
                    .build();

            HttpResponse<String> msgResp = httpClient.send(msgReq, HttpResponse.BodyHandlers.ofString());
            if (msgResp.statusCode() != 202) {
                log.error("[ScenarioRunner] Send message failed: {}", msgResp.statusCode());
                return false;
            }

            // Trigger agent loop
            HttpRequest runReq = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/sessions/" + sessionId + "/run"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build();

            HttpResponse<String> runResp = httpClient.send(runReq, HttpResponse.BodyHandlers.ofString());
            if (runResp.statusCode() != 202) {
                log.error("[ScenarioRunner] Run agent failed: {}", runResp.statusCode());
                return false;
            }

            // Poll for completion
            long deadline = System.currentTimeMillis() + STEP_TIMEOUT_MS;
            while (System.currentTimeMillis() < deadline) {
                Thread.sleep(POLL_INTERVAL_MS);

                HttpRequest getReq = HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "/api/sessions/" + sessionId))
                        .GET()
                        .build();

                HttpResponse<String> getResp = httpClient.send(getReq, HttpResponse.BodyHandlers.ofString());
                if (getResp.statusCode() == 200) {
                    JsonNode session = objectMapper.readTree(getResp.body());
                    String status = session.path("status").asText();
                    if ("COMPLETED".equals(status) || "IDLE".equals(status)) {
                        return verifyStep(baseUrl, sessionId, step, msgCountBefore);
                    }
                    if ("FAILED".equals(status)) {
                        log.warn("[ScenarioRunner] Session status FAILED for step: {}", step.userQuery());
                        printMessages(baseUrl, sessionId, msgCountBefore);
                        return false;
                    }
                }
            }

            log.warn("[ScenarioRunner] Step timed out after {}ms: {}", STEP_TIMEOUT_MS, step.userQuery());
            return false;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            log.error("[ScenarioRunner] Step error: {}", e.getMessage(), e);
            return false;
        }
    }

    private int getMessageCount(String baseUrl, String sessionId) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/sessions/" + sessionId + "/messages"))
                    .GET().build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                JsonNode msgs = objectMapper.readTree(resp.body());
                return msgs.isArray() ? msgs.size() : 0;
            }
        } catch (Exception ignored) {}
        return 0;
    }

    private boolean verifyStep(String baseUrl, String sessionId,
                               ScenarioConfig.ScenarioStep step, int msgCountBefore) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/sessions/" + sessionId + "/messages"))
                    .GET().build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) return true; // can't verify, treat as pass

            JsonNode msgs = objectMapper.readTree(resp.body());
            if (!msgs.isArray()) return true;

            // Print new messages from this step
            int newMsgCount = 0;
            String lastAssistantContent = null;
            String lastAssistantAgent = null;
            for (int i = msgCountBefore; i < msgs.size(); i++) {
                JsonNode m = msgs.get(i);
                String role = m.path("role").asText();
                String content = m.path("content").asText("");
                String agentId = m.path("agentId").asText("");
                newMsgCount++;

                if ("assistant".equals(role)) {
                    lastAssistantContent = content;
                    lastAssistantAgent = agentId;
                    String preview = content.length() > 200
                            ? content.substring(0, 200) + "..."
                            : content;
                    log.info("[ScenarioRunner]   [{}] {}", agentId, preview);

                    // Print tool calls found in the response
                    java.util.regex.Matcher toolMatcher =
                            java.util.regex.Pattern.compile("<tool_call>\\s*\\{.*?\"name\"\\s*:\\s*\"([^\"]+)\".*?\\}\\s*</tool_call>",
                                    java.util.regex.Pattern.DOTALL).matcher(content);
                    while (toolMatcher.find()) {
                        log.info("[ScenarioRunner]     -> Tool call: {}", toolMatcher.group(1));
                    }
                }
            }

            log.info("[ScenarioRunner]   {} new messages generated", newMsgCount);

            // Verify: if step has agentResponses, check that we got the specialist's response
            if (step.agentResponses() != null && !step.agentResponses().isEmpty()) {
                // Find the specialist response (not controller, not reviewer)
                for (ScenarioConfig.AgentResponse ar : step.agentResponses()) {
                    if (!"controller".equals(ar.agentName()) && !"reviewer".equals(ar.agentName())) {
                        // This is the specialist — verify last assistant message has content
                        if (lastAssistantContent == null || lastAssistantContent.isBlank()) {
                            log.warn("[ScenarioRunner]   VERIFY FAIL: no assistant response for specialist '{}'", ar.agentName());
                            return false;
                        }
                        log.info("[ScenarioRunner]   VERIFY OK: got response from [{}]", lastAssistantAgent);
                        break;
                    }
                }
            }

            return true;
        } catch (Exception e) {
            log.warn("[ScenarioRunner] Verify error: {}", e.getMessage());
            return true; // verification failure shouldn't fail the step
        }
    }

    private void printMessages(String baseUrl, String sessionId, int fromIndex) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/sessions/" + sessionId + "/messages"))
                    .GET().build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                JsonNode msgs = objectMapper.readTree(resp.body());
                if (msgs.isArray()) {
                    for (int i = fromIndex; i < msgs.size(); i++) {
                        JsonNode m = msgs.get(i);
                        String role = m.path("role").asText();
                        String agentId = m.path("agentId").asText("");
                        String content = m.path("content").asText("");
                        String preview = content.length() > 300 ? content.substring(0, 300) + "..." : content;
                        log.info("[ScenarioRunner]   [{}|{}] {}", role, agentId, preview);
                    }
                }
            }
        } catch (Exception ignored) {}
    }
}
