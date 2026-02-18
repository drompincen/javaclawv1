package io.github.drompincen.javaclawv1.runtime.agent.llm;

import io.github.drompincen.javaclawv1.persistence.document.EventDocument;
import io.github.drompincen.javaclawv1.persistence.document.MessageDocument;
import io.github.drompincen.javaclawv1.persistence.repository.EventRepository;
import io.github.drompincen.javaclawv1.persistence.repository.MessageRepository;
import io.github.drompincen.javaclawv1.protocol.event.EventType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ScenarioAssertsTest {

    @Mock
    private MongoTemplate mongoTemplate;

    @Mock
    private EventRepository eventRepository;

    @Mock
    private MessageRepository messageRepository;

    private ScenarioAsserts scenarioAsserts;

    private static final String SESSION_ID = "test-session-123";
    private static final String PROJECT_ID = "test-project";

    @BeforeEach
    void setUp() {
        scenarioAsserts = new ScenarioAsserts(mongoTemplate, eventRepository, messageRepository);
    }

    // --- Session status assertions ---

    @Test
    void sessionStatus_matches_passes() {
        ScenarioConfigV2.StepExpectations expects = new ScenarioConfigV2.StepExpectations(
                "COMPLETED", null, null, null);
        ScenarioAsserts.StepContext ctx = new ScenarioAsserts.StepContext(
                SESSION_ID, PROJECT_ID, 0, "COMPLETED");

        List<AssertionResult> results = scenarioAsserts.evaluate(expects, ctx);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).passed()).isTrue();
        assertThat(results.get(0).name()).contains("sessionStatus");
    }

    @Test
    void sessionStatus_mismatch_fails() {
        ScenarioConfigV2.StepExpectations expects = new ScenarioConfigV2.StepExpectations(
                "COMPLETED", null, null, null);
        ScenarioAsserts.StepContext ctx = new ScenarioAsserts.StepContext(
                SESSION_ID, PROJECT_ID, 0, "FAILED");

        List<AssertionResult> results = scenarioAsserts.evaluate(expects, ctx);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).passed()).isFalse();
    }

    // --- Event assertions ---

    @Test
    void eventsContainsTypes_allPresent_passes() {
        List<EventDocument> events = List.of(
                makeEvent(EventType.AGENT_DELEGATED, 1),
                makeEvent(EventType.TOOL_CALL_STARTED, 2),
                makeEvent(EventType.TOOL_RESULT, 3)
        );
        when(eventRepository.findBySessionIdAndSeqGreaterThanOrderBySeqAsc(SESSION_ID, 0))
                .thenReturn(events);

        ScenarioConfigV2.EventExpectations eventExp = new ScenarioConfigV2.EventExpectations(
                List.of("AGENT_DELEGATED", "TOOL_RESULT"), null);
        ScenarioConfigV2.StepExpectations expects = new ScenarioConfigV2.StepExpectations(
                null, eventExp, null, null);
        ScenarioAsserts.StepContext ctx = new ScenarioAsserts.StepContext(
                SESSION_ID, PROJECT_ID, 0, "COMPLETED");

        List<AssertionResult> results = scenarioAsserts.evaluate(expects, ctx);

        assertThat(results).hasSize(2);
        assertThat(results).allMatch(AssertionResult::passed);
    }

    @Test
    void eventsContainsTypes_missingType_fails() {
        List<EventDocument> events = List.of(
                makeEvent(EventType.TOOL_CALL_STARTED, 1)
        );
        when(eventRepository.findBySessionIdAndSeqGreaterThanOrderBySeqAsc(SESSION_ID, 0))
                .thenReturn(events);

        ScenarioConfigV2.EventExpectations eventExp = new ScenarioConfigV2.EventExpectations(
                List.of("AGENT_DELEGATED", "TOOL_RESULT"), null);
        ScenarioConfigV2.StepExpectations expects = new ScenarioConfigV2.StepExpectations(
                null, eventExp, null, null);
        ScenarioAsserts.StepContext ctx = new ScenarioAsserts.StepContext(
                SESSION_ID, PROJECT_ID, 0, "COMPLETED");

        List<AssertionResult> results = scenarioAsserts.evaluate(expects, ctx);

        assertThat(results).hasSize(2);
        assertThat(results).noneMatch(AssertionResult::passed);
    }

    @Test
    void eventsMinCounts_sufficient_passes() {
        List<EventDocument> events = List.of(
                makeEvent(EventType.TOOL_RESULT, 1),
                makeEvent(EventType.TOOL_RESULT, 2),
                makeEvent(EventType.TOOL_CALL_STARTED, 3)
        );
        when(eventRepository.findBySessionIdAndSeqGreaterThanOrderBySeqAsc(SESSION_ID, 0))
                .thenReturn(events);

        ScenarioConfigV2.EventExpectations eventExp = new ScenarioConfigV2.EventExpectations(
                null, Map.of("TOOL_RESULT", 2, "TOOL_CALL_STARTED", 1));
        ScenarioConfigV2.StepExpectations expects = new ScenarioConfigV2.StepExpectations(
                null, eventExp, null, null);
        ScenarioAsserts.StepContext ctx = new ScenarioAsserts.StepContext(
                SESSION_ID, PROJECT_ID, 0, "COMPLETED");

        List<AssertionResult> results = scenarioAsserts.evaluate(expects, ctx);

        assertThat(results).hasSize(2);
        assertThat(results).allMatch(AssertionResult::passed);
    }

    @Test
    void eventsMinCounts_insufficient_fails() {
        List<EventDocument> events = List.of(
                makeEvent(EventType.TOOL_RESULT, 1)
        );
        when(eventRepository.findBySessionIdAndSeqGreaterThanOrderBySeqAsc(SESSION_ID, 0))
                .thenReturn(events);

        ScenarioConfigV2.EventExpectations eventExp = new ScenarioConfigV2.EventExpectations(
                null, Map.of("TOOL_RESULT", 3));
        ScenarioConfigV2.StepExpectations expects = new ScenarioConfigV2.StepExpectations(
                null, eventExp, null, null);
        ScenarioAsserts.StepContext ctx = new ScenarioAsserts.StepContext(
                SESSION_ID, PROJECT_ID, 0, "COMPLETED");

        List<AssertionResult> results = scenarioAsserts.evaluate(expects, ctx);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).passed()).isFalse();
    }

    // --- MongoDB assertions ---

    @Test
    void mongoCountGte_sufficient_passes() {
        when(mongoTemplate.count(any(Query.class), eq("tickets"))).thenReturn(3L);

        ScenarioConfigV2.MongoAssertion ma = new ScenarioConfigV2.MongoAssertion(
                "tickets",
                Map.of("projectId", "sprint-tracker"),
                new ScenarioConfigV2.AssertCondition(null, 2, null, null, null, null));
        ScenarioConfigV2.StepExpectations expects = new ScenarioConfigV2.StepExpectations(
                null, null, List.of(ma), null);
        ScenarioAsserts.StepContext ctx = new ScenarioAsserts.StepContext(
                SESSION_ID, PROJECT_ID, 0, "COMPLETED");

        List<AssertionResult> results = scenarioAsserts.evaluate(expects, ctx);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).passed()).isTrue();
        assertThat(results.get(0).name()).contains("countGte");
    }

    @Test
    void mongoCountGte_insufficient_fails() {
        when(mongoTemplate.count(any(Query.class), eq("tickets"))).thenReturn(1L);

        ScenarioConfigV2.MongoAssertion ma = new ScenarioConfigV2.MongoAssertion(
                "tickets",
                Map.of("projectId", "sprint-tracker"),
                new ScenarioConfigV2.AssertCondition(null, 5, null, null, null, null));
        ScenarioConfigV2.StepExpectations expects = new ScenarioConfigV2.StepExpectations(
                null, null, List.of(ma), null);
        ScenarioAsserts.StepContext ctx = new ScenarioAsserts.StepContext(
                SESSION_ID, PROJECT_ID, 0, "COMPLETED");

        List<AssertionResult> results = scenarioAsserts.evaluate(expects, ctx);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).passed()).isFalse();
    }

    @Test
    void mongoCountEq_exactMatch_passes() {
        when(mongoTemplate.count(any(Query.class), eq("memories"))).thenReturn(0L);

        ScenarioConfigV2.MongoAssertion ma = new ScenarioConfigV2.MongoAssertion(
                "memories",
                Map.of("key", "framework-version"),
                new ScenarioConfigV2.AssertCondition(0, null, null, null, null, null));
        ScenarioConfigV2.StepExpectations expects = new ScenarioConfigV2.StepExpectations(
                null, null, List.of(ma), null);
        ScenarioAsserts.StepContext ctx = new ScenarioAsserts.StepContext(
                SESSION_ID, PROJECT_ID, 0, "COMPLETED");

        List<AssertionResult> results = scenarioAsserts.evaluate(expects, ctx);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).passed()).isTrue();
    }

    @Test
    void mongoExists_true_passes() {
        when(mongoTemplate.count(any(Query.class), eq("memories"))).thenReturn(2L);

        ScenarioConfigV2.MongoAssertion ma = new ScenarioConfigV2.MongoAssertion(
                "memories",
                Map.of("key", "framework-version"),
                new ScenarioConfigV2.AssertCondition(null, null, null, true, null, null));
        ScenarioConfigV2.StepExpectations expects = new ScenarioConfigV2.StepExpectations(
                null, null, List.of(ma), null);
        ScenarioAsserts.StepContext ctx = new ScenarioAsserts.StepContext(
                SESSION_ID, PROJECT_ID, 0, "COMPLETED");

        List<AssertionResult> results = scenarioAsserts.evaluate(expects, ctx);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).passed()).isTrue();
    }

    @Test
    void mongoExists_false_passesWhenEmpty() {
        when(mongoTemplate.count(any(Query.class), eq("memories"))).thenReturn(0L);

        ScenarioConfigV2.MongoAssertion ma = new ScenarioConfigV2.MongoAssertion(
                "memories",
                Map.of("key", "framework-version"),
                new ScenarioConfigV2.AssertCondition(null, null, null, false, null, null));
        ScenarioConfigV2.StepExpectations expects = new ScenarioConfigV2.StepExpectations(
                null, null, List.of(ma), null);
        ScenarioAsserts.StepContext ctx = new ScenarioAsserts.StepContext(
                SESSION_ID, PROJECT_ID, 0, "COMPLETED");

        List<AssertionResult> results = scenarioAsserts.evaluate(expects, ctx);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).passed()).isTrue();
    }

    @Test
    void mongoTemplateVar_sessionId_resolved() {
        when(mongoTemplate.count(any(Query.class), eq("events"))).thenReturn(5L);

        ScenarioConfigV2.MongoAssertion ma = new ScenarioConfigV2.MongoAssertion(
                "events",
                Map.of("sessionId", "{{sessionId}}"),
                new ScenarioConfigV2.AssertCondition(null, 1, null, null, null, null));
        ScenarioConfigV2.StepExpectations expects = new ScenarioConfigV2.StepExpectations(
                null, null, List.of(ma), null);
        ScenarioAsserts.StepContext ctx = new ScenarioAsserts.StepContext(
                SESSION_ID, PROJECT_ID, 0, "COMPLETED");

        List<AssertionResult> results = scenarioAsserts.evaluate(expects, ctx);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).passed()).isTrue();
    }

    // --- Message assertions ---

    @Test
    void lastAssistantContains_found_passes() {
        List<MessageDocument> messages = List.of(
                makeMessage("user", "create a ticket"),
                makeMessage("assistant", "I created a ticket for database migration")
        );
        when(messageRepository.findBySessionIdOrderBySeqAsc(SESSION_ID)).thenReturn(messages);

        ScenarioConfigV2.MessageExpectations msgExp = new ScenarioConfigV2.MessageExpectations(
                "ticket", null, null);
        ScenarioConfigV2.StepExpectations expects = new ScenarioConfigV2.StepExpectations(
                null, null, null, msgExp);
        ScenarioAsserts.StepContext ctx = new ScenarioAsserts.StepContext(
                SESSION_ID, PROJECT_ID, 0, "COMPLETED");

        List<AssertionResult> results = scenarioAsserts.evaluate(expects, ctx);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).passed()).isTrue();
    }

    @Test
    void lastAssistantContains_notFound_fails() {
        List<MessageDocument> messages = List.of(
                makeMessage("user", "create a ticket"),
                makeMessage("assistant", "I created something for the project")
        );
        when(messageRepository.findBySessionIdOrderBySeqAsc(SESSION_ID)).thenReturn(messages);

        ScenarioConfigV2.MessageExpectations msgExp = new ScenarioConfigV2.MessageExpectations(
                "database migration", null, null);
        ScenarioConfigV2.StepExpectations expects = new ScenarioConfigV2.StepExpectations(
                null, null, null, msgExp);
        ScenarioAsserts.StepContext ctx = new ScenarioAsserts.StepContext(
                SESSION_ID, PROJECT_ID, 0, "COMPLETED");

        List<AssertionResult> results = scenarioAsserts.evaluate(expects, ctx);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).passed()).isFalse();
    }

    @Test
    void lastAssistantMatches_regex_passes() {
        List<MessageDocument> messages = List.of(
                makeMessage("assistant", "Created ticket #123 for migration")
        );
        when(messageRepository.findBySessionIdOrderBySeqAsc(SESSION_ID)).thenReturn(messages);

        ScenarioConfigV2.MessageExpectations msgExp = new ScenarioConfigV2.MessageExpectations(
                null, "ticket #\\d+", null);
        ScenarioConfigV2.StepExpectations expects = new ScenarioConfigV2.StepExpectations(
                null, null, null, msgExp);
        ScenarioAsserts.StepContext ctx = new ScenarioAsserts.StepContext(
                SESSION_ID, PROJECT_ID, 0, "COMPLETED");

        List<AssertionResult> results = scenarioAsserts.evaluate(expects, ctx);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).passed()).isTrue();
    }

    @Test
    void anyAssistantContains_found_passes() {
        List<MessageDocument> messages = List.of(
                makeMessage("assistant", "First response"),
                makeMessage("user", "follow up"),
                makeMessage("assistant", "This contains the keyword migration")
        );
        when(messageRepository.findBySessionIdOrderBySeqAsc(SESSION_ID)).thenReturn(messages);

        ScenarioConfigV2.MessageExpectations msgExp = new ScenarioConfigV2.MessageExpectations(
                null, null, "migration");
        ScenarioConfigV2.StepExpectations expects = new ScenarioConfigV2.StepExpectations(
                null, null, null, msgExp);
        ScenarioAsserts.StepContext ctx = new ScenarioAsserts.StepContext(
                SESSION_ID, PROJECT_ID, 0, "COMPLETED");

        List<AssertionResult> results = scenarioAsserts.evaluate(expects, ctx);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).passed()).isTrue();
    }

    @Test
    void nullExpects_returnsEmptyList() {
        ScenarioAsserts.StepContext ctx = new ScenarioAsserts.StepContext(
                SESSION_ID, PROJECT_ID, 0, "COMPLETED");

        List<AssertionResult> results = scenarioAsserts.evaluate(null, ctx);

        assertThat(results).isEmpty();
    }

    @Test
    void combinedAssertions_mixedResults() {
        // Session status passes, event check fails
        List<EventDocument> events = List.of(
                makeEvent(EventType.TOOL_CALL_STARTED, 1)
        );
        when(eventRepository.findBySessionIdAndSeqGreaterThanOrderBySeqAsc(SESSION_ID, 0))
                .thenReturn(events);

        ScenarioConfigV2.EventExpectations eventExp = new ScenarioConfigV2.EventExpectations(
                List.of("AGENT_DELEGATED"), null);
        ScenarioConfigV2.StepExpectations expects = new ScenarioConfigV2.StepExpectations(
                "COMPLETED", eventExp, null, null);
        ScenarioAsserts.StepContext ctx = new ScenarioAsserts.StepContext(
                SESSION_ID, PROJECT_ID, 0, "COMPLETED");

        List<AssertionResult> results = scenarioAsserts.evaluate(expects, ctx);

        assertThat(results).hasSize(2);
        // Session status passes
        assertThat(results.get(0).passed()).isTrue();
        // Event type missing fails
        assertThat(results.get(1).passed()).isFalse();
    }

    // --- Helpers ---

    private EventDocument makeEvent(EventType type, long seq) {
        EventDocument event = new EventDocument();
        event.setSessionId(SESSION_ID);
        event.setType(type);
        event.setSeq(seq);
        event.setTimestamp(Instant.now());
        return event;
    }

    private MessageDocument makeMessage(String role, String content) {
        MessageDocument msg = new MessageDocument();
        msg.setSessionId(SESSION_ID);
        msg.setRole(role);
        msg.setContent(content);
        msg.setTimestamp(Instant.now());
        return msg;
    }
}
