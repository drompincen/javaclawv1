package io.github.drompincen.javaclawv1.runtime.agent.llm;

import io.github.drompincen.javaclawv1.persistence.document.EventDocument;
import io.github.drompincen.javaclawv1.persistence.document.MessageDocument;
import io.github.drompincen.javaclawv1.persistence.repository.EventRepository;
import org.bson.Document;
import io.github.drompincen.javaclawv1.persistence.repository.MessageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import java.util.Set;
import static java.util.Map.entry;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

@Component
@ConditionalOnProperty(name = "javaclaw.scenario.file")
public class ScenarioAsserts {

    private static final Logger log = LoggerFactory.getLogger(ScenarioAsserts.class);

    /** Old collection names → thingCategory values for the unified "things" collection. */
    private static final Map<String, String> THINGS_MAPPING = Map.ofEntries(
            entry("tickets", "TICKET"),
            entry("objectives", "OBJECTIVE"),
            entry("resources", "RESOURCE"),
            entry("blindspots", "BLINDSPOT"),
            entry("checklists", "CHECKLIST"),
            entry("phases", "PHASE"),
            entry("milestones", "MILESTONE"),
            entry("delta_packs", "DELTA_PACK"),
            entry("reminders", "REMINDER"),
            entry("uploads", "UPLOAD"),
            entry("ideas", "IDEA"),
            entry("links", "LINK"),
            entry("intakes", "INTAKE"),
            entry("reconciliations", "RECONCILIATION")
    );

    /** Top-level fields on ThingDocument — all other filter fields get payload. prefix. */
    private static final Set<String> THING_TOP_LEVEL_FIELDS = Set.of(
            "_id", "id", "projectId", "projectName", "thingCategory", "createDate", "updateDate"
    );

    private final MongoTemplate mongoTemplate;
    private final EventRepository eventRepository;
    private final MessageRepository messageRepository;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public ScenarioAsserts(MongoTemplate mongoTemplate,
                           EventRepository eventRepository,
                           MessageRepository messageRepository,
                           ObjectMapper objectMapper) {
        this.mongoTemplate = mongoTemplate;
        this.eventRepository = eventRepository;
        this.messageRepository = messageRepository;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newHttpClient();
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

        // 5. HTTP assertions
        if (expects.http() != null) {
            for (ScenarioConfigV2.HttpAssertion ha : expects.http()) {
                results.addAll(assertHttp(ha, ctx));
            }
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

        // Map old collection names to unified "things" collection
        String thingCategory = THINGS_MAPPING.get(collection);
        String actualCollection = thingCategory != null ? "things" : collection;

        Query query = new Query();
        if (thingCategory != null) {
            query.addCriteria(Criteria.where("thingCategory").is(thingCategory));
        }
        if (ma.filter() != null) {
            for (Map.Entry<String, Object> entry : ma.filter().entrySet()) {
                Object value = resolveTemplateVar(entry.getValue(), ctx);
                String fieldName = entry.getKey();
                // For things collection, prefix domain-specific fields with payload.
                if (thingCategory != null && !THING_TOP_LEVEL_FIELDS.contains(fieldName)) {
                    fieldName = "payload." + fieldName;
                }
                query.addCriteria(Criteria.where(fieldName).is(value));
            }
        }

        long count = mongoTemplate.count(query, actualCollection);

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
            List<Document> docs = mongoTemplate.find(query, Document.class, actualCollection);
            Pattern pattern = Pattern.compile(cond.anyMatchPattern(), Pattern.CASE_INSENSITIVE);
            // For things collection, domain fields are nested under payload
            String fieldName = cond.anyMatchField();
            if (thingCategory != null) {
                fieldName = "payload." + fieldName;
            }
            boolean found = false;
            for (Document doc : docs) {
                Object fieldVal = resolveNestedField(doc, fieldName);
                if (fieldVal != null && pattern.matcher(fieldVal.toString()).find()) {
                    found = true;
                    break;
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

    /** Resolve dotted field paths like "payload.title" from a BSON Document. */
    private Object resolveNestedField(Document doc, String fieldPath) {
        String[] parts = fieldPath.split("\\.");
        Object current = doc;
        for (String part : parts) {
            if (current instanceof Document d) {
                current = d.get(part);
            } else {
                return null;
            }
        }
        return current;
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

    private List<AssertionResult> assertHttp(ScenarioConfigV2.HttpAssertion ha, StepContext ctx) {
        List<AssertionResult> results = new ArrayList<>();
        if (ctx.baseUrl() == null || ha.url() == null) {
            results.add(new AssertionResult("http", false, "configured", "baseUrl or url is null"));
            return results;
        }

        String resolvedUrl = resolveTemplateVar(ha.url(), ctx).toString();
        String fullUrl = ctx.baseUrl() + resolvedUrl;
        String method = ha.method() != null ? ha.method().toUpperCase() : "GET";

        try {
            HttpRequest.Builder reqBuilder = HttpRequest.newBuilder().uri(URI.create(fullUrl));
            if ("POST".equals(method)) {
                reqBuilder.header("Content-Type", "application/json")
                          .POST(HttpRequest.BodyPublishers.ofString("{}"));
            } else {
                reqBuilder.GET();
            }

            HttpResponse<String> resp = httpClient.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofString());
            String body = resp.body();

            // Check status code
            if (ha.expectedStatus() != null) {
                boolean passed = resp.statusCode() == ha.expectedStatus();
                results.add(new AssertionResult(
                        "http[" + method + " " + resolvedUrl + "].status",
                        passed,
                        String.valueOf(ha.expectedStatus()),
                        String.valueOf(resp.statusCode())
                ));
                if (!passed) return results;
            }

            // bodyContains
            if (ha.bodyContains() != null) {
                boolean passed = body != null && body.toLowerCase().contains(ha.bodyContains().toLowerCase());
                results.add(new AssertionResult(
                        "http[" + resolvedUrl + "].bodyContains",
                        passed,
                        "contains '" + ha.bodyContains() + "'",
                        passed ? "found" : truncate(body != null ? body : "null", 200)
                ));
            }

            // bodyMatches (regex)
            if (ha.bodyMatches() != null) {
                boolean passed = body != null && Pattern.compile(ha.bodyMatches(), Pattern.DOTALL | Pattern.CASE_INSENSITIVE).matcher(body).find();
                results.add(new AssertionResult(
                        "http[" + resolvedUrl + "].bodyMatches",
                        passed,
                        "matches /" + ha.bodyMatches() + "/",
                        passed ? "matched" : truncate(body != null ? body : "null", 200)
                ));
            }

            // jsonArrayMinSize
            if (ha.jsonArrayMinSize() != null) {
                try {
                    JsonNode json = objectMapper.readTree(body);
                    int size = json.isArray() ? json.size() : 0;
                    boolean passed = size >= ha.jsonArrayMinSize();
                    results.add(new AssertionResult(
                            "http[" + resolvedUrl + "].jsonArrayMinSize",
                            passed,
                            ">= " + ha.jsonArrayMinSize(),
                            String.valueOf(size)
                    ));
                } catch (Exception e) {
                    results.add(new AssertionResult(
                            "http[" + resolvedUrl + "].jsonArrayMinSize",
                            false, ">= " + ha.jsonArrayMinSize(), "parse error: " + e.getMessage()));
                }
            }

            // jsonPath + jsonPathEquals / jsonPathContains
            if (ha.jsonPath() != null) {
                try {
                    JsonNode json = objectMapper.readTree(body);
                    String extracted = resolveJsonPath(json, ha.jsonPath());

                    if (ha.jsonPathEquals() != null) {
                        boolean passed = ha.jsonPathEquals().equals(extracted);
                        results.add(new AssertionResult(
                                "http[" + resolvedUrl + "].jsonPath(" + ha.jsonPath() + ")==" + ha.jsonPathEquals(),
                                passed,
                                ha.jsonPathEquals(),
                                extracted != null ? extracted : "null"
                        ));
                    }
                    if (ha.jsonPathContains() != null) {
                        boolean passed = extracted != null && extracted.toLowerCase().contains(ha.jsonPathContains().toLowerCase());
                        results.add(new AssertionResult(
                                "http[" + resolvedUrl + "].jsonPath(" + ha.jsonPath() + ") contains '" + ha.jsonPathContains() + "'",
                                passed,
                                "contains '" + ha.jsonPathContains() + "'",
                                extracted != null ? truncate(extracted, 100) : "null"
                        ));
                    }
                } catch (Exception e) {
                    results.add(new AssertionResult(
                            "http[" + resolvedUrl + "].jsonPath",
                            false, ha.jsonPath(), "parse error: " + e.getMessage()));
                }
            }

            // If no specific checks were made, just report status
            if (results.isEmpty()) {
                results.add(new AssertionResult(
                        "http[" + method + " " + resolvedUrl + "]",
                        resp.statusCode() >= 200 && resp.statusCode() < 300,
                        "2xx",
                        String.valueOf(resp.statusCode())
                ));
            }

        } catch (Exception e) {
            log.error("[ScenarioAsserts] HTTP assertion error: {}", e.getMessage(), e);
            results.add(new AssertionResult("http[" + resolvedUrl + "]", false, "success", "error: " + e.getMessage()));
        }

        return results;
    }

    /**
     * Simple JSON path resolver supporting:
     * - $[0].fieldName (array index + field)
     * - $.fieldName (root field)
     * - $[*].fieldName (search all array elements, return first match)
     */
    private String resolveJsonPath(JsonNode root, String path) {
        if (root == null || path == null) return null;

        String p = path.startsWith("$") ? path.substring(1) : path;
        JsonNode current = root;

        while (!p.isEmpty()) {
            if (p.startsWith("[*]")) {
                // Wildcard array: collect all values from remaining path
                p = p.substring(3);
                if (p.startsWith(".")) p = p.substring(1);
                if (current.isArray()) {
                    StringBuilder sb = new StringBuilder();
                    for (JsonNode item : current) {
                        String val = resolveJsonPath(item, "$." + p);
                        if (val != null) {
                            if (sb.length() > 0) sb.append(", ");
                            sb.append(val);
                        }
                    }
                    return sb.length() > 0 ? sb.toString() : null;
                }
                return null;
            } else if (p.startsWith("[")) {
                int end = p.indexOf(']');
                if (end < 0) return null;
                int idx = Integer.parseInt(p.substring(1, end));
                current = current.isArray() && idx < current.size() ? current.get(idx) : null;
                if (current == null) return null;
                p = p.substring(end + 1);
            } else if (p.startsWith(".")) {
                p = p.substring(1);
                int next = findNextPathSeparator(p);
                String field = next < 0 ? p : p.substring(0, next);
                current = current.path(field);
                if (current.isMissingNode()) return null;
                p = next < 0 ? "" : p.substring(next);
            } else {
                int next = findNextPathSeparator(p);
                String field = next < 0 ? p : p.substring(0, next);
                current = current.path(field);
                if (current.isMissingNode()) return null;
                p = next < 0 ? "" : p.substring(next);
            }
        }

        return current.isTextual() ? current.asText() : current.toString();
    }

    private int findNextPathSeparator(String p) {
        int dot = p.indexOf('.');
        int bracket = p.indexOf('[');
        if (dot < 0) return bracket;
        if (bracket < 0) return dot;
        return Math.min(dot, bracket);
    }

    public record StepContext(
            String sessionId,
            String projectId,
            long stepStartEventSeq,
            String sessionStatus,
            String baseUrl
    ) {}
}
