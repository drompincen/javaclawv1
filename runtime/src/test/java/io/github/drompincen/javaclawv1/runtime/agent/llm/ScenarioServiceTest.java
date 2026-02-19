package io.github.drompincen.javaclawv1.runtime.agent.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ScenarioServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private ScenarioService service;

    @BeforeEach
    void setUp() {
        service = new ScenarioService(objectMapper);
    }

    @Test
    void loadV1Scenario_setsConfigCorrectly(@TempDir Path tempDir) throws Exception {
        File file = tempDir.resolve("v1.json").toFile();
        objectMapper.writeValue(file, new ScenarioConfig(
                "Test Project",
                "desc",
                java.util.List.of(
                        new ScenarioConfig.ScenarioStep("hello", "greet", null)
                )
        ));

        service.loadScenario(file.getAbsolutePath());

        assertThat(service.isLoaded()).isTrue();
        assertThat(service.isV2()).isFalse();
        assertThat(service.getProjectName()).isEqualTo("Test Project");
        assertThat(service.getSteps()).hasSize(1);
        assertThat(service.getV2Config()).isNull();
    }

    @Test
    void loadV2Scenario_setsV2ConfigCorrectly(@TempDir Path tempDir) throws Exception {
        // Write a V2 JSON file
        String json = """
                {
                  "schemaVersion": 2,
                  "projectName": "V2 Project",
                  "description": "test",
                  "defaults": {"maxWaitMs": 5000},
                  "steps": [
                    {
                      "name": "step1",
                      "userQuery": "do something",
                      "description": "test step",
                      "agentResponses": [
                        {"agentName": "controller", "responseFallback": "ok"}
                      ],
                      "expects": {"sessionStatus": "COMPLETED"}
                    }
                  ]
                }
                """;
        File file = tempDir.resolve("v2.json").toFile();
        objectMapper.writeValue(file, objectMapper.readTree(json));

        service.loadScenario(file.getAbsolutePath());

        assertThat(service.isLoaded()).isTrue();
        assertThat(service.isV2()).isTrue();
        assertThat(service.getProjectName()).isEqualTo("V2 Project");
        assertThat(service.getV2Config()).isNotNull();
        assertThat(service.getV2Steps()).hasSize(1);
        assertThat(service.getV2Config().defaults().maxWaitMs()).isEqualTo(5000L);
    }

    @Test
    void getResponseForAgent_v2_findsResponse() {
        ScenarioConfigV2 v2 = new ScenarioConfigV2(
                2, "proj", "desc", null,
                java.util.List.of(
                        new ScenarioConfigV2.Step(
                                "step1", "tool-call", "create a ticket", "desc",
                                java.util.List.of(
                                        new ScenarioConfig.AgentResponse("pm", "ticket created")
                                ),
                                null, null, null, null, null
                        )
                )
        );
        service.setConfigV2(v2);

        String response = service.getResponseForAgent("create a ticket", "pm");
        assertThat(response).isEqualTo("ticket created");
    }

    @Test
    void getResponseForAgent_v2_caseInsensitive() {
        ScenarioConfigV2 v2 = new ScenarioConfigV2(
                2, "proj", "desc", null,
                java.util.List.of(
                        new ScenarioConfigV2.Step(
                                "step1", "tool-call", "Create A Ticket", "desc",
                                java.util.List.of(
                                        new ScenarioConfig.AgentResponse("pm", "ticket created")
                                ),
                                null, null, null, null, null
                        )
                )
        );
        service.setConfigV2(v2);

        String response = service.getResponseForAgent("create a ticket", "pm");
        assertThat(response).isEqualTo("ticket created");
    }

    @Test
    void getResponseForAgent_v1_findsResponse() {
        ScenarioConfig v1 = new ScenarioConfig(
                "proj", "desc",
                java.util.List.of(
                        new ScenarioConfig.ScenarioStep("hello", "greet",
                                java.util.List.of(
                                        new ScenarioConfig.AgentResponse("generalist", "hi there")
                                ))
                )
        );
        service.setConfig(v1);

        String response = service.getResponseForAgent("hello", "generalist");
        assertThat(response).isEqualTo("hi there");
    }

    @Test
    void getResponseForAgent_noConfig_returnsNull() {
        assertThat(service.getResponseForAgent("hello", "generalist")).isNull();
    }

    @Test
    void isLoaded_neitherConfigSet_false() {
        assertThat(service.isLoaded()).isFalse();
    }

    @Test
    void deserialize_seedStep(@TempDir Path tempDir) throws Exception {
        String json = """
                {
                  "schemaVersion": 2,
                  "projectName": "Seed Test",
                  "description": "test seed step",
                  "defaults": {"maxWaitMs": 5000},
                  "steps": [
                    {
                      "name": "Seed data",
                      "type": "seed",
                      "seedActions": [
                        {"method": "POST", "url": "/api/projects/{{projectId}}/threads", "body": {"title": "Thread 1", "content": "Content 1"}},
                        {"method": "POST", "url": "/api/projects/{{projectId}}/tickets", "body": {"title": "Ticket 1"}}
                      ]
                    }
                  ]
                }
                """;
        File file = tempDir.resolve("seed.json").toFile();
        objectMapper.writeValue(file, objectMapper.readTree(json));

        service.loadScenario(file.getAbsolutePath());

        assertThat(service.isV2()).isTrue();
        ScenarioConfigV2.Step step = service.getV2Steps().get(0);
        assertThat(step.type()).isEqualTo("seed");
        assertThat(step.seedActions()).hasSize(2);
        assertThat(step.seedActions().get(0).method()).isEqualTo("POST");
        assertThat(step.seedActions().get(0).url()).isEqualTo("/api/projects/{{projectId}}/threads");
        assertThat(step.seedActions().get(1).url()).isEqualTo("/api/projects/{{projectId}}/tickets");
    }

    @Test
    void deserialize_httpAssertion(@TempDir Path tempDir) throws Exception {
        String json = """
                {
                  "schemaVersion": 2,
                  "projectName": "HTTP Assert Test",
                  "description": "test http assertions",
                  "defaults": {"maxWaitMs": 5000},
                  "steps": [
                    {
                      "name": "step1",
                      "userQuery": "test",
                      "agentResponses": [
                        {"agentName": "controller", "responseFallback": "ok"}
                      ],
                      "expects": {
                        "sessionStatus": "COMPLETED",
                        "http": [
                          {
                            "url": "/api/projects/{{projectId}}/objectives",
                            "expectedStatus": 200,
                            "jsonArrayMinSize": 2,
                            "bodyContains": "Evidence"
                          },
                          {
                            "method": "GET",
                            "url": "/api/projects/{{projectId}}/phases",
                            "jsonPath": "$[0].name",
                            "jsonPathContains": "Phase"
                          }
                        ]
                      }
                    }
                  ]
                }
                """;
        File file = tempDir.resolve("http.json").toFile();
        objectMapper.writeValue(file, objectMapper.readTree(json));

        service.loadScenario(file.getAbsolutePath());

        assertThat(service.isV2()).isTrue();
        ScenarioConfigV2.StepExpectations expects = service.getV2Steps().get(0).expects();
        assertThat(expects.http()).hasSize(2);

        ScenarioConfigV2.HttpAssertion ha1 = expects.http().get(0);
        assertThat(ha1.url()).isEqualTo("/api/projects/{{projectId}}/objectives");
        assertThat(ha1.expectedStatus()).isEqualTo(200);
        assertThat(ha1.jsonArrayMinSize()).isEqualTo(2);
        assertThat(ha1.bodyContains()).isEqualTo("Evidence");

        ScenarioConfigV2.HttpAssertion ha2 = expects.http().get(1);
        assertThat(ha2.method()).isEqualTo("GET");
        assertThat(ha2.jsonPath()).isEqualTo("$[0].name");
        assertThat(ha2.jsonPathContains()).isEqualTo("Phase");
    }

    @Test
    void deserialize_seedStepWithExpects(@TempDir Path tempDir) throws Exception {
        String json = """
                {
                  "schemaVersion": 2,
                  "projectName": "Seed + HTTP Test",
                  "description": "seed step with http expects",
                  "steps": [
                    {
                      "name": "Seed and verify",
                      "type": "seed",
                      "seedActions": [
                        {"method": "POST", "url": "/api/projects/{{projectId}}/threads", "body": {"title": "T1"}}
                      ],
                      "expects": {
                        "http": [
                          {"url": "/api/projects/{{projectId}}/threads", "expectedStatus": 200, "jsonArrayMinSize": 1}
                        ]
                      }
                    }
                  ]
                }
                """;
        File file = tempDir.resolve("seed-http.json").toFile();
        objectMapper.writeValue(file, objectMapper.readTree(json));

        service.loadScenario(file.getAbsolutePath());

        ScenarioConfigV2.Step step = service.getV2Steps().get(0);
        assertThat(step.type()).isEqualTo("seed");
        assertThat(step.seedActions()).hasSize(1);
        assertThat(step.expects()).isNotNull();
        assertThat(step.expects().http()).hasSize(1);
        assertThat(step.expects().http().get(0).jsonArrayMinSize()).isEqualTo(1);
    }
}
