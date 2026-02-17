package io.github.drompincen.javaclawv1.runtime.agent;

import io.github.drompincen.javaclawv1.protocol.event.EventType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

@Service
public class WebSearchService {

    private static final Logger log = LoggerFactory.getLogger(WebSearchService.class);

    private final EventService eventService;

    public WebSearchService(EventService eventService) {
        this.eventService = eventService;
    }

    /**
     * Build a Google search URL from the user message, emit a SEARCH_REQUESTED event,
     * and return a markdown placeholder response.
     */
    public String executeWebSearch(String userMessage, String sessionId) {
        // Strip common query prefixes
        String query = userMessage
                .replaceAll("(?i)(what is|what's|tell me|show me|search for|look up|find out|google)\\s*", "")
                .trim();

        String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);
        String searchUrl = "https://www.google.com/search?q=" + encoded;
        String requestId = UUID.randomUUID().toString();

        // Emit the search request event — the UI will open the search pane
        eventService.emit(sessionId, EventType.SEARCH_REQUESTED,
                Map.of("requestId", requestId, "query", query, "searchUrl", searchUrl));

        log.info("[WebSearch] query=\"{}\" requestId={}", query, requestId.substring(0, 8));

        return "**Web Search Requested**\n\n"
                + "I need to search the web for: **" + query + "**\n\n"
                + "The search pane should open automatically with a Google search. "
                + "Please paste the relevant results there and click **Submit**.\n\n"
                + "Once I receive the search results, send another message and I'll incorporate the information.";
    }

    /**
     * Detect whether the (lowercased) user message looks like a web search request.
     * Both a search topic keyword and a search context keyword must be present.
     */
    public boolean isSearchRequest(String lower) {
        boolean hasSearchTopic = lower.contains("weather") || lower.contains("news") || lower.contains("stock")
                || lower.contains("price of") || lower.contains("latest") || lower.contains("current")
                || lower.contains("today") || lower.contains("score") || lower.contains("result of")
                || lower.contains("who won") || lower.contains("when is") || lower.contains("how much")
                || lower.contains("what time") || lower.contains("search for") || lower.contains("google")
                || lower.contains("look up") || lower.contains("find out");
        boolean hasSearchContext = lower.contains("weather") || lower.contains("in ") || lower.contains("for ")
                || lower.contains("about ") || lower.contains("of ");
        return hasSearchTopic && hasSearchContext;
    }
}
