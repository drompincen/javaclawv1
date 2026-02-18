package io.github.drompincen.javaclawv1.runtime.agent.llm;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class TestResponseGenerator {

    private TestResponseGenerator() {}

    public static String generateResponse(String agentId, List<Map<String, String>> messages) {
        String userMsg = getLastUserMessage(messages);
        boolean hasToolResults = messages.stream().anyMatch(m -> "tool".equals(m.get("role")));

        if ("reminder".equals(agentId)) {
            return generateReminderResponse(userMsg);
        }

        return switch (agentId) {
            case "controller" -> generateControllerResponse(userMsg, messages);
            case "reviewer" -> generateReviewerResponse(messages, hasToolResults);
            default -> generateSpecialistResponse(agentId, userMsg, hasToolResults);
        };
    }

    static String generateControllerResponse(String userMsg, List<Map<String, String>> messages) {
        String lower = userMsg.toLowerCase();
        String delegate;
        if (lower.contains("code") || lower.contains("file") || lower.contains("read")
                || lower.contains("list") || lower.contains("write") || lower.contains("run")
                || lower.contains("execute") || lower.contains("debug") || lower.contains("explain")) {
            delegate = "coder";
        } else if (lower.contains("sprint") || lower.contains("ticket") || lower.contains("plan")
                || lower.contains("milestone") || lower.contains("backlog")) {
            delegate = "pm";
        } else if (lower.contains("remind") || lower.contains("schedule") || lower.contains("alarm")) {
            delegate = "reminder";
        } else {
            delegate = "generalist";
        }

        return """
                {"delegate": "%s", "subTask": "%s"}"""
                .formatted(delegate, escapeJson(truncate(userMsg, 150)));
    }

    static String generateReviewerResponse(List<Map<String, String>> messages, boolean hasToolResults) {
        String lastAssistant = messages.stream()
                .filter(m -> "assistant".equals(m.get("role")))
                .map(m -> m.getOrDefault("content", ""))
                .reduce("", (a, b) -> b);

        if (hasToolResults && !lastAssistant.contains("[ERROR]")) {
            return """
                    {"pass": true, "summary": "Specialist completed task with tool results"}""";
        } else if (lastAssistant.contains("[ERROR]")) {
            return """
                    {"pass": false, "feedback": "The specialist encountered an error. Please retry."}""";
        } else {
            return """
                    {"pass": true, "summary": "Test mode — accepted specialist output"}""";
        }
    }

    static String generateSpecialistResponse(String agentId, String userMsg, boolean hasToolResults) {
        if (hasToolResults) {
            return "[TEST] Agent %s completed the task. Tool results were processed successfully for: %s"
                    .formatted(agentId, truncate(userMsg, 200));
        }

        String lower = userMsg.toLowerCase();

        if (lower.contains("list") && (lower.contains("file") || lower.contains("dir") || lower.contains("folder"))) {
            String path = extractPath(userMsg);
            return """
                    I'll list the directory contents for you.

                    <tool_call>
                    {"name": "list_directory", "args": {"path": "%s"}}
                    </tool_call>""".formatted(escapeJson(path));
        }

        if ((lower.contains("read") || lower.contains("explain") || lower.contains("show") || lower.contains("open"))
                && hasFileReference(lower)) {
            String path = extractPath(userMsg);
            return """
                    I'll read that file for you.

                    <tool_call>
                    {"name": "read_file", "args": {"path": "%s"}}
                    </tool_call>""".formatted(escapeJson(path));
        }

        if (lower.contains("search") || lower.contains("find") || lower.contains("grep")) {
            String searchPattern = extractSearchPattern(userMsg);
            return """
                    I'll search for that.

                    <tool_call>
                    {"name": "search_files", "args": {"pattern": "%s", "path": "."}}
                    </tool_call>""".formatted(escapeJson(searchPattern));
        }

        if (lower.contains("run") || lower.contains("execute") || lower.contains("compile") || lower.contains("build")) {
            return """
                    I'll run that command for you.

                    <tool_call>
                    {"name": "shell_exec", "args": {"command": "echo 'Build completed successfully'"}}
                    </tool_call>""";
        }

        return "[TEST] Agent %s processed request: %s".formatted(agentId, truncate(userMsg, 200));
    }

    static String generateReminderResponse(String userMsg) {
        String lower = userMsg.toLowerCase();
        String when = "tomorrow";
        String recurring = "no";

        if (lower.contains("daily") || lower.contains("every day")) {
            recurring = "yes daily";
            when = "daily";
        } else if (lower.contains("weekly") || lower.contains("every week")) {
            recurring = "yes weekly";
            when = "weekly";
        } else if (lower.contains("hourly") || lower.contains("every hour")) {
            recurring = "yes hourly";
            when = "every hour";
        } else if (lower.contains("morning")) {
            when = "tomorrow morning";
        } else if (lower.contains("evening") || lower.contains("night")) {
            when = "this evening";
        }

        String reminderMsg = truncate(userMsg, 150);
        return "I've set up a reminder for you.\n\n"
                + "REMINDER: " + reminderMsg + " | WHEN: " + when + " | RECURRING: " + recurring + "\n\n"
                + "You'll be notified at the scheduled time.";
    }

    public static String getLastUserMessage(List<Map<String, String>> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            if ("user".equals(messages.get(i).get("role"))) {
                return messages.get(i).getOrDefault("content", "");
            }
        }
        return "";
    }

    static String extractPath(String msg) {
        Matcher m1 = Pattern.compile("([A-Z]:\\\\[\\w\\\\.-]+)").matcher(msg);
        if (m1.find()) return m1.group(1);

        Matcher m2 = Pattern.compile("(/[\\w/.-]+)").matcher(msg);
        if (m2.find()) return m2.group(1);

        Matcher m3 = Pattern.compile("\\b([\\w.-]+(?:/[\\w.-]+)*\\.\\w{1,10})\\b").matcher(msg);
        if (m3.find()) return m3.group(1);

        return ".";
    }

    static boolean hasFileReference(String lower) {
        return lower.contains("file") || lower.contains(".xml") || lower.contains(".java")
                || lower.contains(".json") || lower.contains(".md") || lower.contains(".yml")
                || lower.contains(".yaml") || lower.contains(".properties") || lower.contains(".txt")
                || lower.contains("pom") || lower.contains("readme") || lower.contains("config");
    }

    static String extractSearchPattern(String msg) {
        String lower = msg.toLowerCase();
        if (lower.contains("java")) return "**/*.java";
        if (lower.contains("test")) return "**/*Test*.java";
        if (lower.contains("xml")) return "**/*.xml";
        if (lower.contains("config")) return "**/application*.yml";
        return "**/*";
    }

    public static String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }

    public static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }
}
