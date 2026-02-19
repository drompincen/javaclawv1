package io.github.drompincen.javaclawv1.tools;

import io.github.drompincen.javaclawv1.protocol.api.ToolRiskProfile;
import io.github.drompincen.javaclawv1.runtime.tools.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ClassifyContentTool implements Tool {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // Patterns for classification
    private static final Pattern JIRA_KEY = Pattern.compile("[A-Z]{2,10}-\\d+");
    private static final Pattern DATE_PATTERN = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
    private static final Pattern NAME_PATTERN = Pattern.compile("(?:@|Assignee:|Owner:|Attendees?:)\\s*([A-Z][a-z]+(?:\\s+[A-Z][a-z]+)?)");

    @Override public String name() { return "classify_content"; }

    @Override public String description() {
        return "Classify raw content into a content type (jira_dump, confluence_export, meeting_notes, etc.) " +
               "and extract dates, people, and project references.";
    }

    @Override public JsonNode inputSchema() {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        props.putObject("content").put("type", "string")
                .put("description", "The raw content to classify");
        props.putObject("sourceHint").put("type", "string")
                .put("description", "Optional hint about the source (jira, confluence, smartsheet)");
        schema.putArray("required").add("content");
        return schema;
    }

    @Override public JsonNode outputSchema() { return MAPPER.createObjectNode().put("type", "object"); }
    @Override public Set<ToolRiskProfile> riskProfiles() { return Set.of(ToolRiskProfile.READ_ONLY); }

    @Override
    public ToolResult execute(ToolContext ctx, JsonNode input, ToolStream stream) {
        String content = input.path("content").asText("");
        if (content.isBlank()) return ToolResult.failure("'content' is required");

        String sourceHint = input.path("sourceHint").asText(null);
        String lower = content.toLowerCase();

        // Classify content type
        String contentType;
        double confidence;

        if (sourceHint != null && !sourceHint.isBlank()) {
            contentType = classifyFromHint(sourceHint);
            confidence = 0.9;
        } else {
            Map.Entry<String, Double> result = classifyFromContent(lower, content);
            contentType = result.getKey();
            confidence = result.getValue();
        }

        // Extract dates
        ArrayNode dates = MAPPER.createArrayNode();
        Matcher dateMatcher = DATE_PATTERN.matcher(content);
        Set<String> seenDates = new HashSet<>();
        while (dateMatcher.find()) {
            if (seenDates.add(dateMatcher.group())) dates.add(dateMatcher.group());
        }

        // Extract people
        ArrayNode people = MAPPER.createArrayNode();
        Matcher nameMatcher = NAME_PATTERN.matcher(content);
        Set<String> seenPeople = new HashSet<>();
        while (nameMatcher.find()) {
            String name = nameMatcher.group(1).trim();
            if (seenPeople.add(name.toLowerCase())) people.add(name);
        }

        // Extract project references
        ArrayNode projects = MAPPER.createArrayNode();
        Matcher jiraMatcher = JIRA_KEY.matcher(content);
        Set<String> seenProjects = new HashSet<>();
        while (jiraMatcher.find()) {
            String key = jiraMatcher.group();
            String proj = key.split("-")[0];
            if (seenProjects.add(proj)) projects.add(proj);
        }

        stream.progress(100, "Content classified as " + contentType);

        ObjectNode result = MAPPER.createObjectNode();
        result.put("contentType", contentType);
        result.put("confidence", confidence);
        result.set("extractedDates", dates);
        result.set("extractedPeople", people);
        result.set("extractedProjects", projects);
        return ToolResult.success(result);
    }

    private String classifyFromHint(String hint) {
        return switch (hint.toLowerCase()) {
            case "jira" -> "JIRA_DUMP";
            case "confluence" -> "CONFLUENCE_EXPORT";
            case "smartsheet" -> "SMARTSHEET_EXPORT";
            case "meeting", "standup", "retro" -> "MEETING_NOTES";
            case "design" -> "DESIGN_DOC";
            case "links" -> "LINK_LIST";
            case "excel" -> "EXCEL_UPLOAD";
            default -> "FREE_TEXT";
        };
    }

    private Map.Entry<String, Double> classifyFromContent(String lower, String original) {
        // Check for Jira dump (CSV headers or Jira keys)
        if ((lower.contains("key,summary,status") || lower.contains("key\tsummary\tstatus"))
                || (JIRA_KEY.matcher(original).results().count() >= 3 && lower.contains("status"))) {
            return Map.entry("JIRA_DUMP", 0.9);
        }

        // Check for Confluence export
        if (lower.contains("confluence") || lower.contains("space key")
                || (lower.contains("##") && lower.contains("action items") && lower.contains("decisions"))) {
            return Map.entry("CONFLUENCE_EXPORT", 0.85);
        }

        // Check for meeting notes
        if ((lower.contains("meeting") || lower.contains("standup") || lower.contains("retro"))
                && (lower.contains("attendees") || lower.contains("action items") || lower.contains("agenda"))) {
            return Map.entry("MEETING_NOTES", 0.9);
        }

        // Check for Smartsheet / project timeline
        if (lower.contains("milestone") && lower.contains("owner") && lower.contains("status")) {
            return Map.entry("SMARTSHEET_EXPORT", 0.8);
        }

        // Check for design doc
        if (lower.contains("background") && lower.contains("proposal")
                && (lower.contains("alternatives") || lower.contains("api design"))) {
            return Map.entry("DESIGN_DOC", 0.85);
        }

        // Check for link list
        if (lower.lines().filter(l -> l.trim().startsWith("http")).count() >= 3) {
            return Map.entry("LINK_LIST", 0.8);
        }

        return Map.entry("FREE_TEXT", 0.6);
    }
}
