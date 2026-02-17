package io.github.drompincen.javaclawv1.tools;

import io.github.drompincen.javaclawv1.persistence.document.MessageDocument;
import io.github.drompincen.javaclawv1.persistence.repository.MessageRepository;
import io.github.drompincen.javaclawv1.protocol.api.ToolRiskProfile;
import io.github.drompincen.javaclawv1.runtime.tools.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Tool that requests a human to perform a web search.
 * The agent provides a search query. The tool:
 * 1. Constructs a Google search URL
 * 2. Emits a SEARCH_REQUESTED event (UI opens browser + shows paste area)
 * 3. Stores a special "search_request" message in the session
 * 4. Waits for a "search_response" message to appear (human pastes results)
 * 5. Returns the pasted content to the agent
 *
 * The UI handles: opening browser, showing paste area, sending search_response message.
 */
public class HumanSearchTool implements Tool {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private MessageRepository messageRepository;

    @Override public String name() { return "human_search"; }

    @Override public String description() {
        return "Request a human to perform a web search. The agent provides a search query, " +
               "the user's default browser opens with that query, and the user can paste " +
               "the relevant content back. Use this when you need current information from the web " +
               "but don't have direct web access. The tool will wait for the human to respond.";
    }

    @Override public JsonNode inputSchema() {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        props.putObject("query").put("type", "string")
                .put("description", "The search query to send to Google");
        props.putObject("context").put("type", "string")
                .put("description", "Brief explanation of what you're looking for, shown to the human");
        props.putObject("timeout_seconds").put("type", "integer")
                .put("description", "How long to wait for human response (default 300 = 5 minutes)");
        schema.putArray("required").add("query");
        return schema;
    }

    @Override public JsonNode outputSchema() { return MAPPER.createObjectNode().put("type", "object"); }
    @Override public Set<ToolRiskProfile> riskProfiles() { return Set.of(ToolRiskProfile.BROWSER_CONTROL); }

    public void setMessageRepository(MessageRepository messageRepository) {
        this.messageRepository = messageRepository;
    }

    @Override
    public ToolResult execute(ToolContext ctx, JsonNode input, ToolStream stream) {
        String query = input.path("query").asText();
        String context = input.path("context").asText("Agent needs web search results");
        int timeout = input.has("timeout_seconds") ? input.get("timeout_seconds").asInt() : 300;

        if (query.isBlank()) return ToolResult.failure("Search query is required");

        String searchUrl = "https://www.google.com/search?q=" +
                URLEncoder.encode(query, StandardCharsets.UTF_8);

        String requestId = UUID.randomUUID().toString().substring(0, 8);

        // Store search request as a special message so the UI can pick it up
        if (messageRepository != null) {
            MessageDocument reqMsg = new MessageDocument();
            reqMsg.setMessageId("search-req-" + requestId);
            reqMsg.setSessionId(ctx.sessionId());
            reqMsg.setSeq(0); // special seq for search requests
            reqMsg.setRole("search_request");
            reqMsg.setContent(MAPPER.createObjectNode()
                    .put("requestId", requestId)
                    .put("query", query)
                    .put("context", context)
                    .put("searchUrl", searchUrl)
                    .toString());
            reqMsg.setTimestamp(Instant.now());
            messageRepository.save(reqMsg);
        }

        // Emit progress so UI knows to open browser + show paste area
        stream.progress(0, "SEARCH_REQUEST:" + requestId + ":" + searchUrl + ":" + query);
        stream.stdoutDelta("Requesting human search: " + query + "\n");
        stream.stdoutDelta("URL: " + searchUrl + "\n");
        stream.stdoutDelta("Waiting for human to paste results (timeout: " + timeout + "s)...\n");

        // Poll for search response message
        if (messageRepository != null) {
            String responseMessageId = "search-resp-" + requestId;
            long startTime = System.currentTimeMillis();
            long timeoutMs = timeout * 1000L;

            while (System.currentTimeMillis() - startTime < timeoutMs) {
                var response = messageRepository.findById(responseMessageId);
                if (response.isPresent()) {
                    String content = response.get().getContent();
                    stream.progress(100, "Search response received");

                    ObjectNode result = MAPPER.createObjectNode();
                    result.put("query", query);
                    result.put("searchUrl", searchUrl);
                    result.put("content", content);
                    result.put("requestId", requestId);
                    return ToolResult.success(result);
                }

                try { Thread.sleep(2000); } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        // Timeout
        ObjectNode result = MAPPER.createObjectNode();
        result.put("query", query);
        result.put("searchUrl", searchUrl);
        result.put("timedOut", true);
        result.put("content", "Human did not respond within " + timeout + " seconds. The search URL was: " + searchUrl);
        result.put("requestId", requestId);
        return ToolResult.success(result);
    }
}
