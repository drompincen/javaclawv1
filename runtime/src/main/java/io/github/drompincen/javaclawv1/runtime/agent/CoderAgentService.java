package io.github.drompincen.javaclawv1.runtime.agent;

import io.github.drompincen.javaclawv1.persistence.document.MemoryDocument;
import io.github.drompincen.javaclawv1.persistence.document.MemoryDocument.MemoryScope;
import io.github.drompincen.javaclawv1.persistence.document.MessageDocument;
import io.github.drompincen.javaclawv1.persistence.repository.MemoryRepository;
import io.github.drompincen.javaclawv1.persistence.repository.MessageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.regex.Pattern;

/**
 * Handles the coder agent's code execution pipeline.
 * Ported from javaclaw.java's AgentLoop inner methods.
 */
@Service
public class CoderAgentService {

    private static final Logger log = LoggerFactory.getLogger(CoderAgentService.class);

    private static final Pattern RUN_REQUEST_PATTERN = Pattern.compile(
            "^(run|execute|start|launch)\\s+(it|that|this|the code|the program|the script).*$");

    private static final Pattern WRITE_FILE_PATTERN = Pattern.compile(
            "\\$\\$WRITE_FILE:\\s*(.+?)\\$\\$");

    private static final Pattern EXEC_PATTERN = Pattern.compile(
            "\\$\\$EXEC:\\s*(.+?)\\$\\$");

    private static final Pattern CLASS_NAME_PATTERN = Pattern.compile(
            "(?:public\\s+)?class\\s+(\\w+)");

    private static final Pattern CODE_BLOCK_PATTERN = Pattern.compile(
            "```(\\w*)\\n(.*?)```", Pattern.DOTALL);

    private static final Pattern CODE_BLOCK_CONTENT_PATTERN = Pattern.compile(
            "```\\w*\\n(.*?)```", Pattern.DOTALL);

    private static final int OUTPUT_LIMIT = 10_000;
    private static final int COMMAND_TIMEOUT_SECONDS = 30;

    private final MessageRepository messageRepository;
    private final MemoryRepository memoryRepository;

    public CoderAgentService(MessageRepository messageRepository,
                             MemoryRepository memoryRepository) {
        this.messageRepository = messageRepository;
        this.memoryRepository = memoryRepository;
    }

    // -----------------------------------------------------------------------
    // 1. Main entry point
    // -----------------------------------------------------------------------

    /**
     * Main entry point for the coder agent. Detects "run it" requests, calls the LLM,
     * parses tool calls, and auto-extracts and runs code if the user asked.
     *
     * @param userMessage the user's message
     * @param sessionId   the current session id
     * @param llmCaller   a function that takes (agentName, userMessage) and returns the LLM response
     * @return the final response (possibly with execution output appended)
     */
    public String executeCoderWithTools(String userMessage, String sessionId,
                                        BiFunction<String, String, String> llmCaller) {
        String lower = userMessage.toLowerCase().trim();

        // Detect "run it" / "execute it" -- find code from previous session messages and re-run
        boolean isRunRequest = RUN_REQUEST_PATTERN.matcher(lower).matches()
                || "run it".equals(lower) || "execute it".equals(lower);

        if (isRunRequest) {
            return rerunPreviousCode(sessionId);
        }

        // Get LLM response via the caller
        String response = llmCaller.apply("coder", userMessage);

        // Parse tool calls from the response: $$WRITE_FILE: path$$ and $$EXEC: cmd$$
        boolean wantsRun = lower.contains("run") || lower.contains("execute")
                || lower.contains("launch") || lower.contains("try it")
                || lower.contains("and run");
        String toolOutput = processCoderToolCalls(response, wantsRun, sessionId);
        if (toolOutput != null) {
            return response + "\n\n---\n" + toolOutput;
        }

        // If user asked to run and LLM didn't include $$EXEC$$ tags, try to extract code and run it
        if (wantsRun) {
            String autoExec = autoExtractAndRun(response, sessionId);
            if (autoExec != null) {
                return response + "\n\n---\n" + autoExec;
            }
        }

        return response;
    }

    // -----------------------------------------------------------------------
    // 2. Process $$WRITE_FILE$$ and $$EXEC$$ tool calls
    // -----------------------------------------------------------------------

    /**
     * Process $$WRITE_FILE: path$$ and $$EXEC: cmd$$ tool calls embedded in LLM response.
     *
     * @param response  the LLM response text
     * @param autoRun   whether to auto-run detected code
     * @param sessionId the current session id
     * @return the accumulated tool output, or null if no tool calls were found
     */
    String processCoderToolCalls(String response, boolean autoRun, String sessionId) {
        StringBuilder sb = new StringBuilder();
        boolean hasOutput = false;
        String lastWrittenFile = null;

        // Process $$WRITE_FILE: path$$ blocks
        var writeMatcher = WRITE_FILE_PATTERN.matcher(response);
        while (writeMatcher.find()) {
            String filePath = writeMatcher.group(1).trim();
            // Find the nearest code block before this marker
            String code = extractCodeBlockBefore(response, writeMatcher.start());
            if (code != null) {
                try {
                    Files.writeString(Path.of(filePath), code);
                    sb.append("**Wrote file:** `").append(filePath)
                            .append("` (").append(code.length()).append(" bytes)\n\n");
                    lastWrittenFile = filePath;
                    hasOutput = true;
                    log.info("[Coder] wrote file: {} ({} bytes)", filePath, code.length());
                } catch (Exception e) {
                    sb.append("**Error writing** `").append(filePath)
                            .append("`: ").append(e.getMessage()).append("\n\n");
                    hasOutput = true;
                }
            }
        }

        // Process $$EXEC: command$$ blocks
        var execMatcher = EXEC_PATTERN.matcher(response);
        while (execMatcher.find()) {
            String cmd = execMatcher.group(1).trim();
            String result = executeShellCommand(cmd);
            sb.append("**Executed:** `").append(cmd).append("`\n\n");
            sb.append("```\n").append(result).append("\n```\n\n");
            hasOutput = true;
        }

        return hasOutput ? sb.toString() : null;
    }

    // -----------------------------------------------------------------------
    // 3. Auto-extract code blocks and run
    // -----------------------------------------------------------------------

    /**
     * Auto-extract code from a response and run it (when LLM didn't use $$EXEC$$ tags).
     * Looks for ```java and ```python code blocks.
     *
     * @param response  the LLM response text
     * @param sessionId the current session id
     * @return execution output, or null if no runnable code was found
     */
    String autoExtractAndRun(String response, String sessionId) {
        List<Map.Entry<String, String>> codeBlocks = extractCodeBlocks(response);
        for (Map.Entry<String, String> block : codeBlocks) {
            String lang = block.getKey();
            String code = block.getValue();
            if ("java".equals(lang) && code.contains("class ") && code.contains("main(")) {
                return writeAndRunJava(code, sessionId);
            }
            if ("python".equals(lang) || "py".equals(lang)) {
                return writeAndRunPython(code, sessionId);
            }
        }
        return null;
    }

    // -----------------------------------------------------------------------
    // 4. Re-run code from previous session messages
    // -----------------------------------------------------------------------

    /**
     * Search backwards through session messages for code blocks in assistant messages
     * and re-run the first runnable one found.
     *
     * @param sessionId the current session id
     * @return execution output, or an error message if no runnable code was found
     */
    String rerunPreviousCode(String sessionId) {
        List<MessageDocument> messages = messageRepository.findBySessionIdOrderBySeqAsc(sessionId);
        // Search backwards for assistant messages with code blocks
        for (int i = messages.size() - 1; i >= 0; i--) {
            MessageDocument msg = messages.get(i);
            if (!"assistant".equals(msg.getRole())) continue;
            List<Map.Entry<String, String>> codeBlocks = extractCodeBlocks(msg.getContent());
            for (Map.Entry<String, String> block : codeBlocks) {
                String lang = block.getKey();
                String code = block.getValue();
                if ("java".equals(lang) && code.contains("class ") && code.contains("main(")) {
                    return writeAndRunJava(code, sessionId);
                }
                if ("python".equals(lang) || "py".equals(lang)) {
                    return writeAndRunPython(code, sessionId);
                }
            }
        }
        return "I couldn't find any runnable code in the previous messages. "
                + "Please share the code you'd like me to run.";
    }

    // -----------------------------------------------------------------------
    // 5. Write and run Java via jbang
    // -----------------------------------------------------------------------

    /**
     * Extract class name from Java source, write to /tmp/ClassName.java, find jbang, and execute.
     *
     * @param code      the Java source code
     * @param sessionId the current session id
     * @return formatted output with file path and execution result
     */
    String writeAndRunJava(String code, String sessionId) {
        try {
            // Extract class name from code
            var classMatch = CLASS_NAME_PATTERN.matcher(code);
            String className = classMatch.find() ? classMatch.group(1) : "Tool";
            String filePath = "/tmp/" + className + ".java";

            Files.writeString(Path.of(filePath), code);
            log.info("[Coder] wrote {} ({} bytes)", filePath, code.length());

            // Find jbang
            String jbangPath = findJbang();
            if (jbangPath == null) {
                return "**Wrote file:** `" + filePath + "`\n\n"
                        + "**Error:** Could not find `jbang` on this system. "
                        + "Install it with: `curl -Ls https://sh.jbang.dev | bash -s - app setup`\n\n"
                        + "Then run: `jbang " + filePath + "`";
            }

            // Execute
            String result = executeShellCommand(jbangPath + " " + filePath);
            StringBuilder sb = new StringBuilder();
            sb.append("**Wrote file:** `").append(filePath).append("`\n\n");
            sb.append("**Executed:** `jbang ").append(filePath).append("`\n\n");
            sb.append("**Output:**\n```\n").append(result).append("\n```\n");
            log.info("[Coder] executed jbang {} -> {} chars output", filePath, result.length());
            return sb.toString();
        } catch (Exception e) {
            return "**Error running Java code:** " + e.getMessage();
        }
    }

    // -----------------------------------------------------------------------
    // 6. Write and run Python
    // -----------------------------------------------------------------------

    /**
     * Write Python code to a timestamped temp file and execute it.
     *
     * @param code      the Python source code
     * @param sessionId the current session id
     * @return formatted output with file path and execution result
     */
    String writeAndRunPython(String code, String sessionId) {
        try {
            String filePath = "/tmp/tool_" + System.currentTimeMillis() + ".py";
            Files.writeString(Path.of(filePath), code);
            log.info("[Coder] wrote {} ({} bytes)", filePath, code.length());

            String pythonPath = findPython();
            if (pythonPath == null) {
                return "**Wrote file:** `" + filePath + "`\n\n"
                        + "**Error:** Could not find `python3` or `python` on this system.";
            }

            String result = executeShellCommand(pythonPath + " " + filePath);
            StringBuilder sb = new StringBuilder();
            sb.append("**Wrote file:** `").append(filePath).append("`\n\n");
            sb.append("**Executed:** `").append(pythonPath).append(" ").append(filePath).append("`\n\n");
            sb.append("**Output:**\n```\n").append(result).append("\n```\n");
            log.info("[Coder] executed python {} -> {} chars output", filePath, result.length());
            return sb.toString();
        } catch (Exception e) {
            return "**Error running Python code:** " + e.getMessage();
        }
    }

    // -----------------------------------------------------------------------
    // 7. Find jbang binary
    // -----------------------------------------------------------------------

    /**
     * Find jbang binary -- check memory, then PATH, then common locations.
     *
     * @return the path to jbang, or null if not found
     */
    String findJbang() {
        // Check global memory for previously found jbang location
        Optional<MemoryDocument> mem = memoryRepository.findByScopeAndKey(
                MemoryScope.GLOBAL, "jbang_path");
        if (mem.isPresent()) {
            String path = mem.get().getContent();
            if (path != null && Files.isExecutable(Path.of(path))) {
                return path;
            }
        }

        // Check PATH
        if (isCommandAvailable("jbang")) {
            saveToolLocation("jbang_path", "jbang");
            return "jbang";
        }

        // Check common locations
        List<String> commonLocations = List.of(
                "/root/.jbang/bin/jbang",
                "/usr/local/bin/jbang",
                System.getProperty("user.home") + "/.jbang/bin/jbang",
                System.getProperty("user.home") + "/.sdkman/candidates/jbang/current/bin/jbang"
        );
        for (String loc : commonLocations) {
            if (Files.isExecutable(Path.of(loc))) {
                saveToolLocation("jbang_path", loc);
                return loc;
            }
        }

        return null;
    }

    // -----------------------------------------------------------------------
    // 8. Find python binary
    // -----------------------------------------------------------------------

    /**
     * Find python binary -- check python3 first, then python.
     *
     * @return the python command name, or null if not found
     */
    String findPython() {
        if (isCommandAvailable("python3")) return "python3";
        if (isCommandAvailable("python")) return "python";
        return null;
    }

    // -----------------------------------------------------------------------
    // 9. Save tool location to global memory
    // -----------------------------------------------------------------------

    /**
     * Save a tool location to global memory so it can be reused later.
     *
     * @param key  the memory key (e.g. "jbang_path")
     * @param path the path to the tool binary
     */
    void saveToolLocation(String key, String path) {
        Optional<MemoryDocument> existing = memoryRepository.findByScopeAndKey(
                MemoryScope.GLOBAL, key);
        if (existing.isPresent()) {
            MemoryDocument doc = existing.get();
            doc.setContent(path);
            doc.setUpdatedAt(Instant.now());
            memoryRepository.save(doc);
        } else {
            MemoryDocument doc = new MemoryDocument();
            doc.setMemoryId(UUID.randomUUID().toString());
            doc.setScope(MemoryScope.GLOBAL);
            doc.setKey(key);
            doc.setContent(path);
            doc.setCreatedBy("coder");
            doc.setCreatedAt(Instant.now());
            doc.setUpdatedAt(Instant.now());
            memoryRepository.save(doc);
        }
        log.info("[Coder] saved tool location: {} = {}", key, path);
    }

    // -----------------------------------------------------------------------
    // 10. Execute a shell command
    // -----------------------------------------------------------------------

    /**
     * Execute a shell command and return stdout+stderr, with timeout and output truncation.
     * Adds jbang paths to the PATH environment variable.
     *
     * @param command the shell command to execute
     * @return the command output (stdout + stderr combined)
     */
    String executeShellCommand(String command) {
        try {
            ProcessBuilder pb = new ProcessBuilder("bash", "-c", command)
                    .redirectErrorStream(true);

            // Add jbang paths to PATH
            String currentPath = System.getenv("PATH");
            if (currentPath == null) currentPath = "";
            pb.environment().put("PATH", currentPath + ":/root/.jbang/bin:/usr/local/bin");

            Process proc = pb.start();
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                    if (output.length() > OUTPUT_LIMIT) {
                        output.append("... [output truncated]\n");
                        break;
                    }
                }
            }

            boolean finished = proc.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                proc.destroyForcibly();
                output.append("\n[TIMEOUT after ").append(COMMAND_TIMEOUT_SECONDS).append(" seconds]");
            }

            int exitCode = finished ? proc.exitValue() : -1;
            if (exitCode != 0) {
                output.append("\n[Exit code: ").append(exitCode).append("]");
            }

            String commandPreview = command.length() > 80
                    ? command.substring(0, 80) + "..." : command;
            log.info("[Exec] command='{}' exit={} output={} chars",
                    commandPreview, exitCode, output.length());

            return output.toString().trim();
        } catch (Exception e) {
            return "Error executing command: " + e.getMessage();
        }
    }

    // -----------------------------------------------------------------------
    // 11. Extract code blocks from markdown
    // -----------------------------------------------------------------------

    /**
     * Extract code blocks from markdown response.
     *
     * @param text the markdown text
     * @return list of (language, code) pairs
     */
    List<Map.Entry<String, String>> extractCodeBlocks(String text) {
        List<Map.Entry<String, String>> blocks = new ArrayList<>();
        if (text == null) return blocks;

        var matcher = CODE_BLOCK_PATTERN.matcher(text);
        while (matcher.find()) {
            String lang = matcher.group(1).toLowerCase();
            String code = matcher.group(2).trim();
            blocks.add(new AbstractMap.SimpleEntry<>(lang.isEmpty() ? "text" : lang, code));
        }
        return blocks;
    }

    // -----------------------------------------------------------------------
    // 12. Extract code block nearest before a position
    // -----------------------------------------------------------------------

    /**
     * Extract the code block closest before a given position in the text.
     *
     * @param text      the text to search
     * @param beforePos the position to search before
     * @return the code content, or null if none found
     */
    String extractCodeBlockBefore(String text, int beforePos) {
        if (text == null) return null;

        var matcher = CODE_BLOCK_CONTENT_PATTERN.matcher(text);
        String lastCode = null;
        while (matcher.find() && matcher.start() < beforePos) {
            lastCode = matcher.group(1).trim();
        }
        return lastCode;
    }

    // -----------------------------------------------------------------------
    // 13. Check if a command is available on the system
    // -----------------------------------------------------------------------

    /**
     * Check whether a command is available on the system using 'which' (Unix) or 'where' (Windows).
     *
     * @param cmd the command name to check
     * @return true if the command is available
     */
    boolean isCommandAvailable(String cmd) {
        try {
            boolean isWindows = System.getProperty("os.name", "").toLowerCase().contains("win");
            List<String> checkCommand = isWindows
                    ? List.of("where", cmd)
                    : List.of("which", cmd);
            Process p = new ProcessBuilder(checkCommand)
                    .redirectErrorStream(true)
                    .start();
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
