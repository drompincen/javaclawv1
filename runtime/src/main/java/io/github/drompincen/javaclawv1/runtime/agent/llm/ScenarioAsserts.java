package io.github.drompincen.javaclawv1.runtime.agent.llm;

import io.github.drompincen.javaclawv1.persistence.document.EventDocument;
import io.github.drompincen.javaclawv1.persistence.document.MessageDocument;
import io.github.drompincen.javaclawv1.persistence.repository.EventRepository;
import io.github.drompincen.javaclawv1.persistence.repository.MessageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Component
@ConditionalOnProperty(name = "javaclaw.scenario.file")
public class ScenarioAsserts {

    private static final Logger log = LoggerFactory.getLogger(ScenarioAsserts.class);

    private final MongoTemplate mongoTemplate;
    private final EventRepository eventRepository;
    private final MessageRepository messageRepository;

    public ScenarioAsserts(MongoTemplate mongoTemplate,
                           EventRepository eventRepository,
                           MessageRepository messageRepository) {
        this.mongoTemplate = mongoTemplate;
        this.eventRepository = eventRepository;
        this.messageRepository = messageRepository;
    }

    public List<AssertionResult> evaluate(ScenarioConfigV2.StepExpectations expects, StepContext ctx) {
        if (expects == null) return List.of();

        List<AssertionResult> results = new ArrayList<>();

        // 1. Session status assertion
        if (expects.sessionStatus() != null) {
            results.add(assertSessionStatus(expects.sessionStatus(), ctx));
        }

        // 2. Event assertions
        if (expects.events() != null) {
            results.addAll(assertEvents(expects.events(), ctx));
        }

        // 3. MongoDB collection assertions
        if (expects.mongo() != null) {
            for (ScenarioConfigV2.MongoAssertion ma : expects.mongo()) {
                results.add(assertMongo(ma, ctx));
            }
        }

        // 4. Message assertions
        if (expects.messages() != null) {
            results.addAll(assertMessages(expects.messages(), ctx));
        }

        return results;
    }

    private AssertionResult assertSessionStatus(String expectedStatus, StepContext ctx) {
        String actual = ctx.sessionStatus();
        boolean passed = expectedStatus.equals(actual);
        return new AssertionResult(
                "sessionStatus == " + expectedStatus,
                passed,
                expectedStatus,
                actual != null ? actual : "null"
        );
    }

    private List<AssertionResult> assertEvents(ScenarioConfigV2.EventExpectations eventExp, StepContext ctx) {
        List<AssertionResult> results = new ArrayList<>();

        List<EventDocument> stepEvents = eventRepository
                .findBySessionIdAndSeqGreaterThanOrderBySeqAsc(ctx.sessionId(), ctx.stepStartEventSeq());

        List<String> eventTypes = stepEvents.stream()
                .map(e -> e.getType().name())
                .collect(Collectors.toList());

        // containsTypes: check each expected type is present
        if (eventExp.containsTypes() != null) {
            for (String expectedType : eventExp.containsTypes()) {
                boolean found = eventTypes.contains(expectedType);
                results.add(new AssertionResult(
                        "events.containsTypes: " + expectedType,
                        found,
                        expectedType,
                        found ? "present" : "missing (had: " + eventTypes + ")"
                ));
            }
        }

        // minCounts: group by type, verify >= expected
        if (eventExp.minCounts() != null) {
            Map<String, Long> typeCounts = stepEvents.stream()
                    .collect(Collectors.groupingBy(e -> e.getType().name(), Collectors.counting()));

            for (Map.Entry<String, Integer> entry : eventExp.minCounts().entrySet()) {
                long actual = typeCounts.getOrDefault(entry.getKey(), 0L);
                boolean passed = actual >= entry.getValue();
                results.add(new AssertionResult(
                        "events.minCounts[" + entry.getKey() + "] >= " + entry.getValue(),
                        passed,
                        ">= " + entry.getValue(),
                        String.valueOf(actual)
                ));
            }
        }

        return results;
    }

    private AssertionResult assertMongo(ScenarioConfigV2.MongoAssertion ma, StepContext ctx) {
        String collection = ma.collection();
        ScenarioConfigV2.AssertCondition cond = ma.assertCondition();
        if (cond == null) {
            return new AssertionResult("mongo[" + collection + "]", true, "no assertion", "skipped");
        }

        Query query = new Query();
        if (ma.filter() != null) {
            for (Map.Entry<String, Object> entry : ma.filter().entrySet()) {
                Object value = resolveTemplateVar(entry.getValue(), ctx);
                query.addCriteria(Criteria.where(entry.getKey()).is(value));
            }
        }

        long count = mongoTemplate.count(query, collection);

        // countEq
        if (cond.countEq() != null) {
            boolean passed = count == cond.countEq();
            return new AssertionResult(
                    "mongo[" + collection + "].countEq(" + cond.countEq() + ")",
                    passed,
                    String.valueOf(cond.countEq()),
                    String.valueOf(count)
            );
        }

        // countGte
        if (cond.countGte() != null) {
            boolean passed = count >= cond.countGte();
            return new AssertionResult(
                    "mongo[" + collection + "].countGte(" + cond.countGte() + ")",
                    passed,
                    ">= " + cond.countGte(),
                    String.valueOf(count)
            );
        }

        // countLte
        if (cond.countLte() != null) {
            boolean passed = count <= cond.countLte();
            return new AssertionResult(
                    "mongo[" + collection + "].countLte(" + cond.countLte() + ")",
                    passed,
                    "<= " + cond.countLte(),
                    String.valueOf(count)
            );
        }

        // exists
        if (cond.exists() != null) {
            boolean passed = cond.exists() ? count > 0 : count == 0;
            return new AssertionResult(
                    "mongo[" + collection + "].exists(" + cond.exists() + ")",
                    passed,
                    cond.exists() ? "> 0" : "== 0",
                    String.valueOf(count)
            );
        }

        // anyMatchField + anyMatchPattern
        if (cond.anyMatchField() != null && cond.anyMatchPattern() != null) {
            List<?> docs = mongoTemplate.find(query, Object.class, collection);
            Pattern pattern = Pattern.compile(cond.anyMatchPattern(), Pattern.CASE_INSENSITIVE);
            boolean found = false;
            for (Object doc : docs) {
                if (doc instanceof Map<?, ?> map) {
                    Object fieldVal = map.get(cond.anyMatchField());
                    if (fieldVal != null && pattern.matcher(fieldVal.toString()).find()) {
                        found = true;
                        break;
                    }
                }
            }
            return new AssertionResult(
                    "mongo[" + collection + "].anyMatch(" + cond.anyMatchField() + " ~ " + cond.anyMatchPattern() + ")",
                    found,
                    "match found",
                    found ? "found" : "no match in " + docs.size() + " doc(s)"
            );
        }

        return new AssertionResult("mongo[" + collection + "]", true, "no condition", "skipped");
    }

    private List<AssertionResult> assertMessages(ScenarioConfigV2.MessageExpectations msgExp, StepContext ctx) {
        List<AssertionResult> results = new ArrayList<>();

        List<MessageDocument> messages = messageRepository.findBySessionIdOrderBySeqAsc(ctx.sessionId());

        // Find assistant messages
        List<String> assistantContents = messages.stream()
                .filter(m -> "assistant".equals(m.getRole()))
                .map(MessageDocument::getContent)
                .filter(c -> c != null && !c.isBlank())
                .collect(Collectors.toList());

        String lastAssistant = assistantContents.isEmpty() ? null
                : assistantContents.get(assistantContents.size() - 1);

        // lastAssistantContains
        if (msgExp.lastAssistantContains() != null) {
            boolean passed = lastAssistant != null
                    && lastAssistant.toLowerCase().contains(msgExp.lastAssistantContains().toLowerCase());
            results.add(new AssertionResult(
                    "messages.lastAssistantContains",
                    passed,
                    "contains '" + msgExp.lastAssistantContains() + "'",
                    lastAssistant != null ? truncate(lastAssistant, 100) : "null"
            ));
        }

        // lastAssistantMatches (regex)
        if (msgExp.lastAssistantMatches() != null) {
            boolean passed = lastAssistant != null
                    && Pattern.compile(msgExp.lastAssistantMatches(), Pattern.DOTALL | Pattern.CASE_INSENSITIVE)
                    .matcher(lastAssistant).find();
            results.add(new AssertionResult(
                    "messages.lastAssistantMatches",
                    passed,
                    "matches /" + msgExp.lastAssistantMatches() + "/",
                    lastAssistant != null ? truncate(lastAssistant, 100) : "null"
            ));
        }

        // anyAssistantContains
        if (msgExp.anyAssistantContains() != null) {
            boolean found = assistantContents.stream()
                    .anyMatch(c -> c.toLowerCase().contains(msgExp.anyAssistantContains().toLowerCase()));
            results.add(new AssertionResult(
                    "messages.anyAssistantContains",
                    found,
                    "any contains '" + msgExp.anyAssistantContains() + "'",
                    found ? "found" : "not found in " + assistantContents.size() + " message(s)"
            ));
        }

        return results;
    }

    private Object resolveTemplateVar(Object value, StepContext ctx) {
        if (!(value instanceof String s)) return value;
        return s.replace("{{sessionId}}", ctx.sessionId() != null ? ctx.sessionId() : "")
                .replace("{{projectId}}", ctx.projectId() != null ? ctx.projectId() : "");
    }

    private String truncate(String s, int maxLen) {
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }

    public record StepContext(
            String sessionId,
            String projectId,
            long stepStartEventSeq,
            String sessionStatus
    ) {}
}
