package io.github.drompincen.javaclawv1.gateway;

import io.github.drompincen.javaclawv1.gateway.test.TestMongoConfiguration;
import io.github.drompincen.javaclawv1.runtime.agent.llm.ScenarioRunner;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.io.File;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "javaclaw.llm.provider=test",
                "javaclaw.scenario.autorun=false",
                "javaclaw.scheduler.enabled=false"
        }
)
@ActiveProfiles("scenario")
@Import(TestMongoConfiguration.class)
class ScenarioIntegrationTest {

    @LocalServerPort
    int port;

    @Autowired
    ScenarioRunner scenarioRunner;

    @BeforeAll
    static void init() {
        System.setProperty("javaclaw.llm.provider", "test");
    }

    @BeforeEach
    void setup() {
        scenarioRunner.setServerPort(port);
        scenarioRunner.cleanMongoDB();
    }

    @ParameterizedTest(name = "Group {0}: {1}")
    @MethodSource("scenarioGroups")
    void scenarioGroup(int group, String name, List<String> files) {
        List<String> failures = new ArrayList<>();
        for (String file : files) {
            scenarioRunner.cleanMongoDB();
            String filePath = resolveScenarioFile(file);
            assertNotNull(filePath, "Scenario file not found: " + file);
            if (!scenarioRunner.runSingleScenario(filePath)) {
                failures.add(file);
            }
        }
        assertTrue(failures.isEmpty(),
                "Group " + group + " had " + failures.size() + " failures: " + failures);
    }

    private String resolveScenarioFile(String scenarioName) {
        String fileName = scenarioName + ".json";
        // Try classpath first (testResources includes scenario-testing/scenarios/)
        URL url = getClass().getClassLoader().getResource(fileName);
        if (url != null) {
            return url.getPath();
        }
        // Fallback to filesystem
        File f = new File("scenario-testing/scenarios/" + fileName);
        if (f.exists()) {
            return f.getAbsolutePath();
        }
        return null;
    }

    static Stream<Arguments> scenarioGroups() {
        return Stream.of(
                Arguments.of(1, "Foundations", GROUP_1),
                Arguments.of(2, "Tools & Single Agents", GROUP_2),
                Arguments.of(3, "Pipelines & Multi-Agent", GROUP_3),
                Arguments.of(4, "E2E Stories & Context Assembly", GROUP_4)
        );
    }

    // ── Group 1: Foundations (13 scenarios) ──
    static final List<String> GROUP_1 = List.of(
            "scenario-general",
            "scenario-coder",
            "scenario-pm",
            "scenario-memory",
            "scenario-fs-tools",
            "scenario-git-tools",
            "scenario-http",
            "scenario-jbang-exec",
            "scenario-python-exec",
            "scenario-exec-time",
            "scenario-pm-tools",
            "scenario-intake-triage",
            "scenario-intake-pipeline"
    );

    // ── Group 2: Tools & Single Agents (14 scenarios) ──
    static final List<String> GROUP_2 = List.of(
            "scenario-memory-v2",
            "scenario-fs-tools-v2",
            "scenario-pm-tools-v2",
            "scenario-coder-exec",
            "scenario-excel-weather",
            "scenario-checklist-agent",
            "scenario-objective-agent",
            "scenario-plan-agent",
            "scenario-reconcile-agent",
            "scenario-resource-agent",
            "scenario-thread-agent",
            "scenario-extraction-v2",
            "scenario-unallocated-resources",
            "scenario-unassigned-tickets"
    );

    // ── Group 3: Pipelines & Multi-Agent (11 scenarios) ──
    // Note: scenario-all-agents-seeded and scenario-agent-merge excluded due to
    // tool permission issues (blocked disallowed tool calls in embedded mode)
    static final List<String> GROUP_3 = List.of(
            "scenario-generalist-intake",
            "scenario-generalist-seeded",
            "scenario-story-1-intake",
            "scenario-story-1-reintake",
            "scenario-thread-update-on-reintake",
            "scenario-thread-intake-v2",
            "scenario-file-upload",
            "scenario-thread-merge",
            "scenario-story-9-memory",
            "scenario-story-3-sprint-objectives",
            "scenario-story-4-resource-load"
    );

    // ── Group 4: E2E Stories & Context Assembly (13 scenarios) ──
    static final List<String> GROUP_4 = List.of(
            "scenario-story-1-full-pipeline",
            "scenario-story-2-alignment",
            "scenario-story-2-pipeline",
            "scenario-story-5-plan-creation",
            "scenario-story-6-checklist",
            "scenario-story-7-scheduled-reconcile",
            "scenario-story-8-ondemand-agents",
            "scenario-story-10-daily-reset",
            "scenario-ask-claw",
            "scenario-ask-claw-capacity",
            "scenario-ask-claw-resources",
            "scenario-ask-claw-sprint-health",
            "scenario-ask-claw-utilization"
    );
}
