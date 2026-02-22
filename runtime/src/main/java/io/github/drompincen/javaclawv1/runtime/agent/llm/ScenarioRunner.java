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

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

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
    private final Map<String, Object> stepVariables = new HashMap<>();

    @Autowired(required = false)
    private ToolMockRegistry toolMockRegistry;

    @Autowired(required = false)
    private ScenarioAsserts scenarioAsserts;

    @Autowired
    private org.springframework.data.mongodb.core.MongoTemplate mongoTemplate;

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
                // Guard: testMode must be active for scenario testing
                String provider = System.getProperty("javaclaw.llm.provider");
                if (!"test".equals(provider)) {
                    log.error("[ScenarioRunner] ABORT: testMode is NOT active (javaclaw.llm.provider={}). " +
                              "Scenario tests require --testMode flag to prevent real LLM calls.", provider);
                    System.exit(2);
                    return;
                }

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

        // Clean MongoDB state from previous runs (preserve agents collection)
        cleanMongoDB();

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
            stepVariables.clear();
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

                // Scope responses to current step so pipeline fallback doesn't replay earlier steps
                scenarioService.setCurrentStepIndex(i);

                ScenarioReport.StepReport stepReport;
                if ("seed".equals(step.type())) {
                    stepReport = playSeedStep(baseUrl, step, i + 1, total);
                    // After seed, discover thread IDs for {{threads[N].threadId}} template resolution
                    if (scenarioProjectId != null) {
                        discoverThreadIds(baseUrl, scenarioProjectId);
                    }
                } else if ("upload".equals(step.type())) {
                    stepReport = playUploadStep(baseUrl, step, i + 1, total);
                } else if ("pipeline".equals(step.type())) {
                    stepReport = playPipelineStep(baseUrl, step, defaults, i + 1, total);
                } else {
                    stepReport = playV2Step(baseUrl, sessionId, step, defaults, i + 1, total);
                }
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
            // Reset scenario response counters and set project ID for template resolution
            scenarioService.resetCounters();
            if (scenarioProjectId != null) {
                scenarioService.setProjectId(scenarioProjectId);
            }

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
                        finalStatus,
                        baseUrl
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

    // ======================== Pipeline Step Playback ========================

    /**
     * Plays a pipeline step: creates a project, calls POST /api/intake/pipeline,
     * polls for completion, then runs assertions against project data.
     */
    private ScenarioReport.StepReport playPipelineStep(String baseUrl, ScenarioConfigV2.Step step,
                                                        ScenarioConfigV2.Defaults defaults,
                                                        int stepNum, int totalSteps) {
        String stepName = step.name() != null ? step.name() : "pipeline";
        try {
            // 1. Ensure project exists
            String projectId = scenarioProjectId;
            if (projectId == null) {
                projectId = ensureProject(baseUrl, scenarioService.getProjectName());
                scenarioProjectId = projectId;
            }
            if (projectId == null) {
                return new ScenarioReport.StepReport(stepName, false,
                        List.of(new AssertionResult("createProject", false, "projectId", "null")));
            }

            // 2. Reset scenario response counters and set project ID for template resolution
            scenarioService.resetCounters();
            scenarioService.setProjectId(projectId);

            // 2b. Set up tool mocks if defined on this step
            if (toolMockRegistry != null && step.toolMocks() != null) {
                toolMockRegistry.setCurrentStepMocks(step.toolMocks());
            }

            // 3. Call POST /api/intake/pipeline
            String content = step.pipelineContent() != null ? step.pipelineContent() : step.userQuery();
            java.util.Map<String, Object> pipelineBody = new java.util.LinkedHashMap<>();
            pipelineBody.put("projectId", projectId);
            pipelineBody.put("content", content);

            // Merge explicit filePaths with any uploaded file paths from prior upload steps
            List<String> allFilePaths = new ArrayList<>();
            if (step.filePaths() != null) {
                allFilePaths.addAll(step.filePaths());
            }
            @SuppressWarnings("unchecked")
            List<String> uploadedPaths = (List<String>) stepVariables.get("_uploadedFilePaths");
            if (uploadedPaths != null) {
                allFilePaths.addAll(uploadedPaths);
            }
            if (!allFilePaths.isEmpty()) {
                pipelineBody.put("filePaths", allFilePaths);
            }

            String bodyJson = objectMapper.writeValueAsString(pipelineBody);
            HttpRequest pipelineReq = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/intake/pipeline"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(bodyJson))
                    .build();

            HttpResponse<String> pipelineResp = httpClient.send(pipelineReq, HttpResponse.BodyHandlers.ofString());
            if (pipelineResp.statusCode() != 200 && pipelineResp.statusCode() != 202) {
                return new ScenarioReport.StepReport(stepName, false,
                        List.of(new AssertionResult("startPipeline", false, "200|202",
                                pipelineResp.statusCode() + ": " + pipelineResp.body())));
            }

            JsonNode pipelineResult = objectMapper.readTree(pipelineResp.body());
            String sourceSessionId = pipelineResult.path("sessionId").asText(null);
            String pipelineId = pipelineResult.path("pipelineId").asText(null);
            log.info("[ScenarioRunner] Pipeline started: id={}, session={}", pipelineId, sourceSessionId);

            // 4. Poll source session for completion
            long timeoutMs = defaults != null && defaults.maxWaitMs() != null ? defaults.maxWaitMs() : 60_000;
            long deadline = System.currentTimeMillis() + timeoutMs;
            String finalStatus = null;
            while (System.currentTimeMillis() < deadline) {
                Thread.sleep(POLL_INTERVAL_MS);
                HttpRequest getReq = HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "/api/sessions/" + sourceSessionId))
                        .GET().build();
                HttpResponse<String> getResp = httpClient.send(getReq, HttpResponse.BodyHandlers.ofString());
                if (getResp.statusCode() == 200) {
                    JsonNode session = objectMapper.readTree(getResp.body());
                    finalStatus = session.path("status").asText();
                    if ("COMPLETED".equals(finalStatus)) break;
                    if ("FAILED".equals(finalStatus)) {
                        log.warn("[ScenarioRunner] Pipeline session FAILED");
                        break;
                    }
                }
            }
            if (finalStatus == null) finalStatus = "TIMEOUT";

            log.info("[ScenarioRunner] Pipeline finished: status={}", finalStatus);

            // Clear tool mocks
            if (toolMockRegistry != null) {
                toolMockRegistry.clearMocks();
            }

            // 5. Run assertions if present
            if (step.expects() != null && scenarioAsserts != null) {
                ScenarioAsserts.StepContext ctx = new ScenarioAsserts.StepContext(
                        sourceSessionId, projectId, 0, finalStatus, baseUrl);
                List<AssertionResult> results = scenarioAsserts.evaluate(step.expects(), ctx);

                boolean allPassed = true;
                for (AssertionResult ar : results) {
                    String tag = ar.passed() ? "PASS" : "FAIL";
                    log.info("[ScenarioRunner]   [{}] {}: expected={}, actual={}",
                            tag, ar.name(), ar.expected(), ar.actual());
                    if (!ar.passed()) allPassed = false;
                }
                return new ScenarioReport.StepReport(stepName, allPassed, results);
            }

            // No expects: just check pipeline completed
            boolean passed = "COMPLETED".equals(finalStatus);
            return new ScenarioReport.StepReport(stepName, passed,
                    List.of(new AssertionResult("pipelineStatus", passed, "COMPLETED", finalStatus)));

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new ScenarioReport.StepReport(stepName, false,
                    List.of(new AssertionResult("interrupted", false, "completed", "interrupted")));
        } catch (Exception e) {
            log.error("[ScenarioRunner] Pipeline step error: {}", e.getMessage(), e);
            return new ScenarioReport.StepReport(stepName, false,
                    List.of(new AssertionResult("exception", false, "no error", e.getMessage())));
        }
    }

    // ======================== Upload Step Playback ========================

    /**
     * Plays an upload step: uploads files via multipart POST to /api/intake/upload.
     * Stores uploaded file paths in stepVariables for use by subsequent pipeline steps.
     */
    private ScenarioReport.StepReport playUploadStep(String baseUrl, ScenarioConfigV2.Step step,
                                                      int stepNum, int totalSteps) {
        String stepName = step.name() != null ? step.name() : "upload";
        try {
            // 1. Ensure project exists
            String projectId = scenarioProjectId;
            if (projectId == null) {
                projectId = ensureProject(baseUrl, scenarioService.getProjectName());
                scenarioProjectId = projectId;
            }
            if (projectId == null) {
                return new ScenarioReport.StepReport(stepName, false,
                        List.of(new AssertionResult("createProject", false, "projectId", "null")));
            }

            if (step.uploadFiles() == null || step.uploadFiles().isEmpty()) {
                log.warn("[ScenarioRunner] Upload step has no uploadFiles — skipping");
                return new ScenarioReport.StepReport(stepName, true, List.of());
            }

            // 2. Build multipart/form-data request body
            String boundary = "----ScenarioUpload" + UUID.randomUUID().toString().replace("-", "");
            ByteArrayOutputStream baos = new ByteArrayOutputStream();

            // projectId field
            baos.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
            baos.write("Content-Disposition: form-data; name=\"projectId\"\r\n\r\n".getBytes(StandardCharsets.UTF_8));
            baos.write((projectId + "\r\n").getBytes(StandardCharsets.UTF_8));

            // File parts
            for (String resourcePath : step.uploadFiles()) {
                InputStream is = getClass().getClassLoader().getResourceAsStream(resourcePath);
                // Fallback: try filesystem paths (relative to cwd, then scenario-testing/scenarios/)
                if (is == null) {
                    java.io.File f = new java.io.File(resourcePath);
                    if (!f.exists()) f = new java.io.File("scenario-testing/scenarios/" + resourcePath);
                    if (f.exists()) {
                        is = new java.io.FileInputStream(f);
                    }
                }
                if (is == null) {
                    return new ScenarioReport.StepReport(stepName, false,
                            List.of(new AssertionResult("loadFile", false, resourcePath, "not found on classpath or filesystem")));
                }
                byte[] fileBytes = is.readAllBytes();
                is.close();

                String fileName = resourcePath.contains("/")
                        ? resourcePath.substring(resourcePath.lastIndexOf('/') + 1)
                        : resourcePath;

                baos.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
                baos.write(("Content-Disposition: form-data; name=\"files\"; filename=\"" + fileName + "\"\r\n").getBytes(StandardCharsets.UTF_8));
                baos.write("Content-Type: application/octet-stream\r\n\r\n".getBytes(StandardCharsets.UTF_8));
                baos.write(fileBytes);
                baos.write("\r\n".getBytes(StandardCharsets.UTF_8));
            }

            baos.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
            byte[] body = baos.toByteArray();

            // 3. POST to /api/intake/upload
            HttpRequest uploadReq = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/intake/upload"))
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .build();

            HttpResponse<String> uploadResp = httpClient.send(uploadReq, HttpResponse.BodyHandlers.ofString());
            if (uploadResp.statusCode() != 200) {
                return new ScenarioReport.StepReport(stepName, false,
                        List.of(new AssertionResult("upload", false, "200",
                                uploadResp.statusCode() + ": " + uploadResp.body())));
            }

            // 4. Parse response and store file paths
            JsonNode uploadResult = objectMapper.readTree(uploadResp.body());
            List<String> filePaths = new ArrayList<>();
            if (uploadResult.isArray()) {
                for (JsonNode item : uploadResult) {
                    String filePath = item.path("filePath").asText(null);
                    if (filePath != null) filePaths.add(filePath);
                }
            }
            stepVariables.put("_uploadedFilePaths", filePaths);
            log.info("[ScenarioRunner] Uploaded {} file(s), paths: {}", filePaths.size(), filePaths);

            // 5. Run assertions if present
            if (step.expects() != null && scenarioAsserts != null) {
                ScenarioAsserts.StepContext ctx = new ScenarioAsserts.StepContext(
                        null, projectId, 0, null, baseUrl);
                List<AssertionResult> results = scenarioAsserts.evaluate(step.expects(), ctx);

                boolean allPassed = true;
                for (AssertionResult ar : results) {
                    String tag = ar.passed() ? "PASS" : "FAIL";
                    log.info("[ScenarioRunner]   [{}] {}: expected={}, actual={}",
                            tag, ar.name(), ar.expected(), ar.actual());
                    if (!ar.passed()) allPassed = false;
                }
                return new ScenarioReport.StepReport(stepName, allPassed, results);
            }

            // No expects: just check files were uploaded
            boolean passed = !filePaths.isEmpty();
            return new ScenarioReport.StepReport(stepName, passed,
                    List.of(new AssertionResult("uploadCount", passed,
                            ">0", String.valueOf(filePaths.size()))));

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new ScenarioReport.StepReport(stepName, false,
                    List.of(new AssertionResult("interrupted", false, "completed", "interrupted")));
        } catch (Exception e) {
            log.error("[ScenarioRunner] Upload step error: {}", e.getMessage(), e);
            return new ScenarioReport.StepReport(stepName, false,
                    List.of(new AssertionResult("exception", false, "no error", e.getMessage())));
        }
    }

    // ======================== Seed Step Playback ========================

    /**
     * Plays a seed step: pre-populates project data via REST POST/PUT calls.
     * Each seedAction is executed in order; all must succeed (2xx).
     */
    private ScenarioReport.StepReport playSeedStep(String baseUrl, ScenarioConfigV2.Step step,
                                                    int stepNum, int totalSteps) {
        String stepName = step.name() != null ? step.name() : "seed";
        try {
            // Ensure project exists
            String projectId = scenarioProjectId;
            if (projectId == null) {
                projectId = ensureProject(baseUrl, scenarioService.getProjectName());
                scenarioProjectId = projectId;
            }
            if (projectId == null) {
                return new ScenarioReport.StepReport(stepName, false,
                        List.of(new AssertionResult("createProject", false, "projectId", "null")));
            }

            if (step.seedActions() == null || step.seedActions().isEmpty()) {
                log.warn("[ScenarioRunner] Seed step has no seedActions — skipping");
                return new ScenarioReport.StepReport(stepName, true, List.of());
            }

            List<AssertionResult> results = new ArrayList<>();

            for (int i = 0; i < step.seedActions().size(); i++) {
                ScenarioConfigV2.SeedAction action = step.seedActions().get(i);
                String method = action.method() != null ? action.method().toUpperCase() : "POST";
                String url = action.url().replace("{{projectId}}", projectId);
                String bodyJson = action.body() != null ? objectMapper.writeValueAsString(action.body()) : "{}";

                // Resolve {{projectId}} in body
                bodyJson = bodyJson.replace("{{projectId}}", projectId);

                HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + url))
                        .header("Content-Type", "application/json");

                if ("PUT".equals(method)) {
                    reqBuilder.PUT(HttpRequest.BodyPublishers.ofString(bodyJson));
                } else {
                    reqBuilder.POST(HttpRequest.BodyPublishers.ofString(bodyJson));
                }

                HttpResponse<String> resp = httpClient.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofString());
                boolean ok = resp.statusCode() >= 200 && resp.statusCode() < 300;

                String label = method + " " + url;
                if (!ok) {
                    log.warn("[ScenarioRunner] Seed action failed: {} → {} {}", label, resp.statusCode(), resp.body());
                } else {
                    log.info("[ScenarioRunner]   Seed: {} → {}", label, resp.statusCode());
                }

                results.add(new AssertionResult(
                        "seed[" + (i + 1) + "] " + label,
                        ok,
                        "2xx",
                        String.valueOf(resp.statusCode())
                ));

                if (!ok) break;
            }

            // Run assertions if present
            if (step.expects() != null && scenarioAsserts != null) {
                ScenarioAsserts.StepContext ctx = new ScenarioAsserts.StepContext(
                        null, projectId, 0, null, baseUrl);
                results.addAll(scenarioAsserts.evaluate(step.expects(), ctx));
            }

            boolean allPassed = results.stream().allMatch(AssertionResult::passed);

            for (AssertionResult ar : results) {
                String tag = ar.passed() ? "PASS" : "FAIL";
                log.info("[ScenarioRunner]   [{}] {}: expected={}, actual={}",
                        tag, ar.name(), ar.expected(), ar.actual());
            }

            log.info("[ScenarioRunner]   STEP {}/{}: {} ({} seed actions)",
                    stepNum, totalSteps, allPassed ? "PASS" : "FAIL", step.seedActions().size());

            return new ScenarioReport.StepReport(stepName, allPassed, results);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new ScenarioReport.StepReport(stepName, false,
                    List.of(new AssertionResult("interrupted", false, "completed", "interrupted")));
        } catch (Exception e) {
            log.error("[ScenarioRunner] Seed step error: {}", e.getMessage(), e);
            return new ScenarioReport.StepReport(stepName, false,
                    List.of(new AssertionResult("exception", false, "no error", e.getMessage())));
        }
    }

    // ======================== MongoDB Cleanup ========================

    /** Collections to preserve during cleanup (agents must persist for orchestration). */
    private static final Set<String> PRESERVED_COLLECTIONS = Set.of("agents");

    /**
     * Clean all MongoDB collections except preserved ones (agents).
     * This ensures test isolation between scenario runs.
     */
    private void cleanMongoDB() {
        try {
            Set<String> collections = mongoTemplate.getCollectionNames();
            int dropped = 0;
            for (String col : collections) {
                if (!PRESERVED_COLLECTIONS.contains(col) && !col.startsWith("system.")) {
                    mongoTemplate.dropCollection(col);
                    dropped++;
                }
            }
            log.info("[ScenarioRunner] MongoDB cleanup: dropped {} collections (preserved: {})",
                    dropped, PRESERVED_COLLECTIONS);
        } catch (Exception e) {
            log.warn("[ScenarioRunner] MongoDB cleanup failed: {}", e.getMessage());
        }
    }

    // ======================== Thread ID Discovery ========================

    /**
     * After seed steps, query project threads and store their IDs in ScenarioService
     * for {{threads[N].threadId}} template resolution in subsequent mock responses.
     */
    private void discoverThreadIds(String baseUrl, String projectId) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/projects/" + projectId + "/threads"))
                    .GET().build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                JsonNode threads = objectMapper.readTree(resp.body());
                List<String> ids = new ArrayList<>();
                if (threads.isArray()) {
                    for (JsonNode t : threads) {
                        String threadId = t.path("threadId").asText(null);
                        if (threadId != null) ids.add(threadId);
                    }
                }
                scenarioService.setThreadIds(ids);
                log.info("[ScenarioRunner] Discovered {} thread IDs for template resolution: {}", ids.size(), ids);
            }
        } catch (Exception e) {
            log.warn("[ScenarioRunner] Failed to discover thread IDs: {}", e.getMessage());
        }
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
                log.info("[ScenarioRunner] Created project '{}' (id={})", projectName, projectId);
                return projectId;
            }
            if (resp.statusCode() == 409) {
                // Project already exists — look it up
                return findProjectByName(baseUrl, projectName);
            }
            log.warn("[ScenarioRunner] Project creation returned {}: {}", resp.statusCode(), resp.body());
        } catch (Exception e) {
            log.warn("[ScenarioRunner] Failed to create project '{}': {}", projectName, e.getMessage());
        }
        return null;
    }

    private String findProjectByName(String baseUrl, String projectName) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/projects"))
                    .GET().build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                JsonNode projects = objectMapper.readTree(resp.body());
                if (projects.isArray()) {
                    for (JsonNode p : projects) {
                        if (projectName.equalsIgnoreCase(p.path("name").asText())) {
                            String projectId = p.path("projectId").asText(null);
                            log.info("[ScenarioRunner] Found existing project '{}' (id={})", projectName, projectId);
                            return projectId;
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("[ScenarioRunner] Failed to find project '{}': {}", projectName, e.getMessage());
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
