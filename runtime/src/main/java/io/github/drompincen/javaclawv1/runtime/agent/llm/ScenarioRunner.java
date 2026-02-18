package io.github.drompincen.javaclawv1.runtime.agent.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Auto-plays scenarios after server startup via REST API calls.
 * Supports both V1 (shallow pass/fail) and V2 (assertions engine) scenarios.
 * Supports multiple scenarios in one JVM via javaclaw.scenario.files property.
 * Auto-exits after all scenarios complete.
 */
@Component
@ConditionalOnProperty(name = "javaclaw.scenario.file")
public class ScenarioRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ScenarioRunner.class);
    private static final long STARTUP_DELAY_MS = 100;
    private static final long POLL_INTERVAL_MS = 200;
    private static final long STEP_TIMEOUT_MS = 30_000;

    private final ScenarioService scenarioService;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private String scenarioProjectId;

    @Autowired(required = false)
    private ToolMockRegistry toolMockRegistry;

    @Autowired(required = false)
    private ScenarioAsserts scenarioAsserts;

    @Value("${server.port:8080}")
    private int serverPort;

    public ScenarioRunner(ScenarioService scenarioService, ObjectMapper objectMapper) {
        this.scenarioService = scenarioService;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newHttpClient();
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        Thread runner = new Thread(() -> {
            try {
                // Check for multi-scenario mode
                String scenarioFiles = System.getProperty("javaclaw.scenario.files");
                if (scenarioFiles != null && scenarioFiles.contains(",")) {
                    playMultipleScenarios(scenarioFiles);
                } else {
                    // Single scenario (backward compat)
                    if (!scenarioService.isLoaded()) {
                        log.warn("[ScenarioRunner] ScenarioService not loaded — skipping");
                        System.exit(1);
                        return;
                    }
                    boolean passed = playSingleScenario();
                    System.exit(passed ? 0 : 1);
                }
            } catch (Exception e) {
                log.error("[ScenarioRunner] Fatal error: {}", e.getMessage(), e);
                System.exit(2);
            }
        }, "ScenarioRunner");
        runner.setDaemon(true);
        runner.start();
    }

    // ======================== Multi-Scenario Orchestration ========================

    private void playMultipleScenarios(String scenarioFiles) {
        List<String> files = Arrays.stream(scenarioFiles.split(","))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .toList();

        log.info("[ScenarioRunner] ============================================================");
        log.info("[ScenarioRunner]  Multi-Scenario Mode — {} scenarios", files.size());
        log.info("[ScenarioRunner] ============================================================");

        // Wait for server startup once
        try {
            log.info("[ScenarioRunner] Waiting {}ms for server startup...", STARTUP_DELAY_MS);
            Thread.sleep(STARTUP_DELAY_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.exit(2);
            return;
        }

        int totalScenarios = files.size();
        int scenariosPassed = 0;
        List<String> results = new ArrayList<>();

        for (int s = 0; s < files.size(); s++) {
            String filePath = files.get(s);
            log.info("[ScenarioRunner] [{}/{}] Loading scenario: {}", s + 1, totalScenarios, filePath);

            // Reload scenario
            scenarioService.reset();
            scenarioProjectId = null;
            scenarioService.loadScenario(filePath);

            if (!scenarioService.isLoaded()) {
                log.error("[ScenarioRunner] [{}/{}] Failed to load — skipping", s + 1, totalScenarios);
                results.add("  FAIL  " + filePath + " (load error)");
                continue;
            }

            boolean passed;
            if (scenarioService.isV2()) {
                passed = playScenarioV2Internal();
            } else {
                passed = playScenarioV1Internal();
            }

            if (passed) {
                scenariosPassed++;
                results.add("  PASS  " + scenarioService.getProjectName());
            } else {
                results.add("  FAIL  " + scenarioService.getProjectName());
            }
        }

        // Print combined summary
        log.info("[ScenarioRunner] ============================================================");
        log.info("[ScenarioRunner]  RESULTS:");
        for (String r : results) {
            log.info("[ScenarioRunner] {}", r);
        }
        log.info("[ScenarioRunner]");
        log.info("[ScenarioRunner]  SUMMARY: {} passed, {} failed out of {} scenarios",
                scenariosPassed, totalScenarios - scenariosPassed, totalScenarios);
        log.info("[ScenarioRunner] ============================================================");

        System.exit(scenariosPassed == totalScenarios ? 0 : 1);
    }

    // ======================== Single Scenario Entry ========================

    private boolean playSingleScenario() {
        if (scenarioService.isV2()) {
            return playScenarioV2Internal();
        } else {
            return playScenarioV1Internal();
        }
    }

    // ======================== V1 Scenario Playback ========================

    private boolean playScenarioV1Internal() {
        try {
            // Only sleep if this is single-scenario mode (multi-scenario already waited)
            String scenarioFiles = System.getProperty("javaclaw.scenario.files");
            if (scenarioFiles == null || !scenarioFiles.contains(",")) {
                log.info("[ScenarioRunner] Waiting {}ms for server startup...", STARTUP_DELAY_MS);
                Thread.sleep(STARTUP_DELAY_MS);
            }

            String baseUrl = "http://localhost:" + serverPort;
            List<ScenarioConfig.ScenarioStep> steps = scenarioService.getSteps();

            log.info("[ScenarioRunner] Playing scenario '{}' with {} steps",
                    scenarioService.getProjectName(), steps.size());

            String sessionId = createSession(baseUrl);
            if (sessionId == null) {
                log.error("[ScenarioRunner] Failed to create session — aborting");
                return false;
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

            return passed == total;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.info("[ScenarioRunner] Interrupted");
            return false;
        } catch (Exception e) {
            log.error("[ScenarioRunner] Unexpected error: {}", e.getMessage(), e);
            return false;
        }
    }

    // ======================== V2 Scenario Playback ========================

    private boolean playScenarioV2Internal() {
        try {
            // Only sleep if this is single-scenario mode
            String scenarioFiles = System.getProperty("javaclaw.scenario.files");
            if (scenarioFiles == null || !scenarioFiles.contains(",")) {
                log.info("[ScenarioRunner] Waiting {}ms for server startup...", STARTUP_DELAY_MS);
                Thread.sleep(STARTUP_DELAY_MS);
            }

            String baseUrl = "http://localhost:" + serverPort;
            ScenarioConfigV2 v2 = scenarioService.getV2Config();
            List<ScenarioConfigV2.Step> steps = v2.steps();
            ScenarioConfigV2.Defaults defaults = v2.defaults();

            log.info("[ScenarioRunner] Playing V2 scenario '{}' with {} steps",
                    v2.projectName(), steps.size());

            // Ensure project exists so context commands (use project) find it
            if (v2.projectName() != null && !v2.projectName().isBlank()) {
                scenarioProjectId = ensureProject(baseUrl, v2.projectName());
            }

            String sessionId = createSession(baseUrl);
            if (sessionId == null) {
                log.error("[ScenarioRunner] Failed to create session — aborting");
                return false;
            }
            log.info("[ScenarioRunner] Created session: {}", sessionId);

            int passed = 0;
            int total = steps.size();
            List<ScenarioReport.StepReport> stepReports = new ArrayList<>();

            for (int i = 0; i < steps.size(); i++) {
                ScenarioConfigV2.Step step = steps.get(i);
                String stepLabel = step.name() != null ? step.name() : step.userQuery();
                log.info("[ScenarioRunner] Step {}/{}: '{}'", i + 1, total, stepLabel);

                ScenarioReport.StepReport stepReport = playV2Step(baseUrl, sessionId, step, defaults, i + 1, total);
                stepReports.add(stepReport);
                if (stepReport.allPassed()) {
                    passed++;
                }
            }

            ScenarioReport report = new ScenarioReport(
                    v2.projectName(), total, passed, stepReports);

            // Log the formatted report
            for (String line : report.toSummary().split("\n")) {
                if (report.allPassed()) {
                    log.info("[ScenarioRunner] {}", line);
                } else {
                    log.warn("[ScenarioRunner] {}", line);
                }
            }

            return report.allPassed();

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.info("[ScenarioRunner] Interrupted");
            return false;
        } catch (Exception e) {
            log.error("[ScenarioRunner] Unexpected error: {}", e.getMessage(), e);
            return false;
        }
    }

    private ScenarioReport.StepReport playV2Step(String baseUrl, String sessionId,
                                ScenarioConfigV2.Step step, ScenarioConfigV2.Defaults defaults,
                                int stepNum, int totalSteps) {
        String stepName = step.name() != null ? step.name() : step.userQuery();
        try {
            // Record current max event seq for scoping assertions
            long stepStartEventSeq = getMaxEventSeq(baseUrl, sessionId);

            // Set up tool mocks for this step
            if (toolMockRegistry != null && step.toolMocks() != null) {
                toolMockRegistry.setCurrentStepMocks(step.toolMocks());
            }

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
                return new ScenarioReport.StepReport(stepName, false,
                        List.of(new AssertionResult("sendMessage", false, "202", String.valueOf(msgResp.statusCode()))));
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
                return new ScenarioReport.StepReport(stepName, false,
                        List.of(new AssertionResult("runAgent", false, "202", String.valueOf(runResp.statusCode()))));
            }

            // Determine timeout
            long timeoutMs = STEP_TIMEOUT_MS;
            if (defaults != null && defaults.maxWaitMs() != null) {
                timeoutMs = defaults.maxWaitMs();
            }

            // Poll for completion
            long deadline = System.currentTimeMillis() + timeoutMs;
            String finalStatus = null;
            while (System.currentTimeMillis() < deadline) {
                Thread.sleep(POLL_INTERVAL_MS);

                HttpRequest getReq = HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "/api/sessions/" + sessionId))
                        .GET()
                        .build();

                HttpResponse<String> getResp = httpClient.send(getReq, HttpResponse.BodyHandlers.ofString());
                if (getResp.statusCode() == 200) {
                    JsonNode session = objectMapper.readTree(getResp.body());
                    finalStatus = session.path("status").asText();
                    if ("COMPLETED".equals(finalStatus) || "IDLE".equals(finalStatus)) {
                        break;
                    }
                    if ("FAILED".equals(finalStatus)) {
                        log.warn("[ScenarioRunner] Session status FAILED for step: {}", step.userQuery());
                        break;
                    }
                }
            }

            if (finalStatus == null) {
                log.warn("[ScenarioRunner] Step timed out after {}ms", timeoutMs);
                finalStatus = "TIMEOUT";
            }

            // Clear tool mocks
            if (toolMockRegistry != null) {
                toolMockRegistry.clearMocks();
            }

            // Run assertions if expects block is present
            if (step.expects() != null && scenarioAsserts != null) {
                ScenarioAsserts.StepContext ctx = new ScenarioAsserts.StepContext(
                        sessionId,
                        scenarioProjectId != null ? scenarioProjectId : scenarioService.getProjectName(),
                        stepStartEventSeq,
                        finalStatus
                );

                List<AssertionResult> results = scenarioAsserts.evaluate(step.expects(), ctx);

                // Print assertion report
                boolean allPassed = true;
                for (AssertionResult ar : results) {
                    String tag = ar.passed() ? "PASS" : "FAIL";
                    log.info("[ScenarioRunner]   [{}] {}: expected={}, actual={}",
                            tag, ar.name(), ar.expected(), ar.actual());
                    if (!ar.passed()) allPassed = false;
                }

                long passCount = results.stream().filter(AssertionResult::passed).count();
                String stepResult = allPassed ? "PASS" : "FAIL";
                log.info("[ScenarioRunner]   STEP {}/{}: {} ({}/{} assertions)",
                        stepNum, totalSteps, stepResult, passCount, results.size());

                return new ScenarioReport.StepReport(stepName, allPassed, results);
            }

            // Fallback: V1-style verification (non-blank assistant message)
            if ("FAILED".equals(finalStatus) || "TIMEOUT".equals(finalStatus)) {
                log.warn("[ScenarioRunner]   STEP {}/{}: FAIL (status={})", stepNum, totalSteps, finalStatus);
                return new ScenarioReport.StepReport(stepName, false,
                        List.of(new AssertionResult("sessionStatus", false, "COMPLETED|IDLE", finalStatus)));
            }

            log.info("[ScenarioRunner]   STEP {}/{}: PASS (no expects block, status={})",
                    stepNum, totalSteps, finalStatus);
            return new ScenarioReport.StepReport(stepName, true, List.of());

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new ScenarioReport.StepReport(stepName, false,
                    List.of(new AssertionResult("interrupted", false, "completed", "interrupted")));
        } catch (Exception e) {
            log.error("[ScenarioRunner] V2 step error: {}", e.getMessage(), e);
            return new ScenarioReport.StepReport(stepName, false,
                    List.of(new AssertionResult("exception", false, "no error", e.getMessage())));
        }
    }

    private long getMaxEventSeq(String baseUrl, String sessionId) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/sessions/" + sessionId + "/events?last=1"))
                    .GET().build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                JsonNode body = objectMapper.readTree(resp.body());
                if (body.isArray() && body.size() > 0) {
                    return body.get(body.size() - 1).path("seq").asLong(0);
                }
            }
        } catch (Exception ignored) {}
        return 0;
    }

    // ======================== Shared HTTP Helpers ========================

    private String ensureProject(String baseUrl, String projectName) {
        try {
            String body = objectMapper.writeValueAsString(
                    java.util.Map.of("name", projectName, "description", "Auto-created by scenario runner"));
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/projects"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                JsonNode project = objectMapper.readTree(resp.body());
                String projectId = project.path("projectId").asText(null);
                log.info("[ScenarioRunner] Ensured project '{}' (id={})", projectName, projectId);
                return projectId;
            }
            log.warn("[ScenarioRunner] Project creation returned {}: {}", resp.statusCode(), resp.body());
        } catch (Exception e) {
            log.warn("[ScenarioRunner] Failed to create project '{}': {}", projectName, e.getMessage());
        }
        return null;
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
            int msgCountBefore = getMessageCount(baseUrl, sessionId);

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
            if (resp.statusCode() != 200) return true;

            JsonNode msgs = objectMapper.readTree(resp.body());
            if (!msgs.isArray()) return true;

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

                    java.util.regex.Matcher toolMatcher =
                            java.util.regex.Pattern.compile("<tool_call>\\s*\\{.*?\"name\"\\s*:\\s*\"([^\"]+)\".*?\\}\\s*</tool_call>",
                                    java.util.regex.Pattern.DOTALL).matcher(content);
                    while (toolMatcher.find()) {
                        log.info("[ScenarioRunner]     -> Tool call: {}", toolMatcher.group(1));
                    }
                }
            }

            log.info("[ScenarioRunner]   {} new messages generated", newMsgCount);

            if (step.agentResponses() != null && !step.agentResponses().isEmpty()) {
                for (ScenarioConfig.AgentResponse ar : step.agentResponses()) {
                    if (!"controller".equals(ar.agentName()) && !"reviewer".equals(ar.agentName())) {
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
            return true;
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
