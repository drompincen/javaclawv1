package io.github.drompincen.javaclawv1.runtime.agent;

import io.github.drompincen.javaclawv1.persistence.document.MessageDocument;
import io.github.drompincen.javaclawv1.persistence.repository.MessageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.regex.Pattern;

/**
 * Smart file tool service: context-aware path resolution, auto-read, LLM analysis.
 * Ported from javaclaw.java's executeFileTool / isFileRequest / extractPaths.
 */
@Service
public class FileAgentService {

    private static final Logger log = LoggerFactory.getLogger(FileAgentService.class);

    private static final Pattern UNIX_PATH_PATTERN = Pattern.compile("(/[\\w.\\-]+){2,}");
    private static final Pattern WINDOWS_PATH_PATTERN = Pattern.compile("[A-Za-z]:\\\\[\\w.\\\\\\-]+");

    private static final String TEXT_EXTENSIONS_REGEX =
            ".*\\.(txt|md|json|csv|xml|yaml|yml|properties|cfg|conf|ini|log|java|py|js|ts|html|css|sh|bat|sql)$";

    private final MessageRepository messageRepository;

    public FileAgentService(MessageRepository messageRepository) {
        this.messageRepository = messageRepository;
    }

    /**
     * Main entry point for the smart file tool.
     *
     * @param userMessage the user's message
     * @param sessionId   the current session id
     * @param llmCaller   a function taking (agentName, userMessage) and returning the LLM response,
     *                    or null if no LLM is available
     * @return the file tool result string
     */
    public String executeFileTool(String userMessage, String sessionId,
                                  BiFunction<String, String, String> llmCaller) {
        var paths = extractPaths(userMessage);
        String lower = userMessage.toLowerCase();

        // Context-aware: if no paths in current message, check recent session messages backwards
        if (paths.isEmpty()) {
            var recentMsgs = messageRepository.findBySessionIdOrderBySeqAsc(sessionId);
            for (int i = recentMsgs.size() - 1; i >= 0 && paths.isEmpty(); i--) {
                paths = extractPaths(recentMsgs.get(i).getContent());
            }
        }

        var sb = new StringBuilder();

        // List directory operation
        if (lower.contains("list dir") || lower.contains("list folder") || lower.contains("ls ")) {
            String dirPath = paths.isEmpty() ? "." : paths.get(0);
            try {
                var dir = Path.of(dirPath);
                if (!Files.isDirectory(dir)) {
                    return "**Error:** `" + dirPath + "` is not a directory.";
                }
                sb.append("**Directory listing of** `").append(dirPath).append("`:\n\n");
                sb.append("| Name | Type | Size |\n|------|------|------|\n");
                try (var stream = Files.list(dir)) {
                    stream.sorted().forEach(p -> {
                        boolean isDir = Files.isDirectory(p);
                        String size = "-";
                        if (!isDir) {
                            try { size = Files.size(p) + " B"; } catch (Exception ignored) {}
                        }
                        sb.append("| ").append(p.getFileName()).append(" | ")
                          .append(isDir ? "DIR" : "FILE").append(" | ").append(size).append(" |\n");
                    });
                }
                log.info("[FileTool] list_directory path={}", dirPath);
                return sb.toString();
            } catch (Exception e) {
                return "**Error listing directory** `" + dirPath + "`: " + e.getMessage();
            }
        }

        // Default: read file(s)
        if (paths.isEmpty()) {
            return "I couldn't find a file path in your request. Please provide a full path like `/path/to/file.java`.";
        }

        for (String path : paths) {
            try {
                var filePath = Path.of(path);
                if (!Files.exists(filePath)) {
                    sb.append("**File not found:** `").append(path).append("`\n\n");
                    continue;
                }

                if (Files.isDirectory(filePath)) {
                    // Auto-list AND collect readable text files in the directory
                    sb.append("**Directory listing of** `").append(path).append("`:\n\n");
                    sb.append("| Name | Type | Size |\n|------|------|------|\n");
                    var readableFiles = new ArrayList<Path>();
                    try (var dirStream = Files.list(filePath)) {
                        dirStream.sorted().forEach(p2 -> {
                            boolean d = Files.isDirectory(p2);
                            String sz = "-";
                            if (!d) {
                                try { sz = Files.size(p2) + " B"; } catch (Exception ignored) {}
                                // Collect readable text files (< 50KB, text-like extensions)
                                String fname = p2.getFileName().toString().toLowerCase();
                                if (fname.matches(TEXT_EXTENSIONS_REGEX)) {
                                    try {
                                        if (Files.size(p2) < 50_000) readableFiles.add(p2);
                                    } catch (Exception ignored) {}
                                }
                            }
                            sb.append("| ").append(p2.getFileName()).append(" | ")
                              .append(d ? "DIR" : "FILE").append(" | ").append(sz).append(" |\n");
                        });
                    } catch (Exception de) {
                        sb.append("Error listing: ").append(de.getMessage()).append("\n");
                    }

                    // If user asked to "read" files (not just list), auto-read them
                    boolean wantsRead = lower.contains("read") || lower.contains("show")
                            || lower.contains("contents") || lower.contains("open") || lower.contains("what");
                    if (wantsRead && !readableFiles.isEmpty()) {
                        sb.append("\n---\n**Reading ").append(readableFiles.size()).append(" text file(s):**\n\n");
                        for (var rf : readableFiles) {
                            try {
                                String fc = Files.readString(rf);
                                if (fc.length() > 5_000) fc = fc.substring(0, 5_000) + "\n... [truncated]";
                                String ext2 = rf.getFileName().toString();
                                ext2 = ext2.contains(".") ? ext2.substring(ext2.lastIndexOf('.') + 1) : "";
                                sb.append("**").append(rf.getFileName()).append(":**\n");
                                sb.append("```").append(ext2).append("\n").append(fc).append("\n```\n\n");
                                log.info("[FileTool] auto-read {}", rf);
                            } catch (Exception re) {
                                sb.append("Error reading ").append(rf.getFileName()).append(": ")
                                  .append(re.getMessage()).append("\n\n");
                            }
                        }
                    } else if (!wantsRead && !readableFiles.isEmpty()) {
                        sb.append("\n**").append(readableFiles.size()).append(" readable file(s) found.** ")
                          .append("Options:\n1. **Read files** -- say \"read the files\"\n")
                          .append("2. **Do nothing** -- continue chatting\n")
                          .append("3. **Let's chat** -- ask me about something else\n");
                    }
                    sb.append("\n");
                    log.info("[FileTool] auto-list directory path={} readable={}", path, readableFiles.size());
                    continue;
                }

                // Regular file
                long size = Files.size(filePath);
                String content = Files.readString(filePath);
                // Truncate very large files
                if (content.length() > 10_000) {
                    content = content.substring(0, 10_000) + "\n... [truncated, " + size + " bytes total]";
                }
                String ext = path.contains(".") ? path.substring(path.lastIndexOf('.') + 1) : "";
                sb.append("**File:** `").append(path).append("` (").append(size).append(" bytes)\n\n");
                sb.append("```").append(ext).append("\n").append(content).append("\n```\n\n");
                log.info("[FileTool] read_file path={} size={}", path, size);
            } catch (Exception e) {
                sb.append("**Error reading** `").append(path).append("`: ").append(e.getMessage()).append("\n\n");
            }
        }

        // If the user's message has an analysis intent, pass file contents to the LLM for analysis
        String fileContent = sb.toString();
        boolean hasAnalysisIntent = lower.contains("tell me") || lower.contains("about")
                || lower.contains("remind") || lower.contains("summarize") || lower.contains("analyze")
                || lower.contains("suggest") || lower.contains("what should") || lower.contains("help me")
                || lower.contains("extract") || lower.contains("find");

        if (hasAnalysisIntent && llmCaller != null && !fileContent.isBlank()) {
            // Save file content as context, then let the LLM analyze it
            long seq = messageRepository.countBySessionId(sessionId) + 1;
            MessageDocument fileCtxMsg = new MessageDocument();
            fileCtxMsg.setMessageId(UUID.randomUUID().toString());
            fileCtxMsg.setSessionId(sessionId);
            fileCtxMsg.setSeq(seq);
            fileCtxMsg.setRole("assistant");
            fileCtxMsg.setContent("[File Tool Results]\n" + fileContent);
            fileCtxMsg.setTimestamp(Instant.now());
            fileCtxMsg.setAgentId("file_tool");
            fileCtxMsg.setMocked(false);
            messageRepository.save(fileCtxMsg);

            // Now call the LLM with the generalist prompt + full context
            String llmAnalysis = llmCaller.apply("generalist", userMessage);
            return fileContent + "\n---\n**Analysis:**\n\n" + llmAnalysis;
        }

        return fileContent;
    }

    /**
     * Detect requests to read/inspect/list files.
     *
     * @param lower the user message in lowercase
     * @return true if the message is a file-related request
     */
    public boolean isFileRequest(String lower) {
        // Check for file path patterns (absolute or relative)
        boolean hasPath = lower.matches(".*(/[a-z0-9_.\\-]+){2,}.*")
                || lower.matches(".*[a-z]:\\\\.*")
                || lower.matches(".*\\.[a-z]{1,5}\\b.*");
        boolean hasFileVerb = lower.contains("read ") || lower.contains("show ") || lower.contains("cat ")
                || lower.contains("contents of") || lower.contains("open ") || lower.contains("view ")
                || lower.contains("list dir") || lower.contains("list folder") || lower.contains("ls ")
                || lower.contains("what's in") || lower.contains("inspect ");
        // Also detect context references like "read those files", "read that file", "open it"
        boolean isContextRef = (lower.contains("read") || lower.contains("open") || lower.contains("show"))
                && (lower.contains("those file") || lower.contains("that file") || lower.contains("the file")
                || lower.contains("those dir") || lower.contains("that dir"));
        return (hasPath && hasFileVerb) || isContextRef;
    }

    /**
     * Check if the message contains file/directory paths.
     *
     * @param msg the message to check
     * @return true if the message contains Unix absolute paths or Windows paths
     */
    public boolean containsFilePath(String msg) {
        return msg.matches(".*(/[\\w.\\-]+){2,}.*")            // Unix absolute path
                || msg.matches(".*[A-Za-z]:\\\\[\\w.\\\\\\-]+.*"); // Windows path
    }

    /**
     * Extract file paths from a user message.
     *
     * @param message the message to extract paths from
     * @return a list of extracted file paths (Unix and Windows)
     */
    public List<String> extractPaths(String message) {
        var paths = new ArrayList<String>();
        // Match absolute Unix paths
        var unixMatcher = UNIX_PATH_PATTERN.matcher(message);
        while (unixMatcher.find()) paths.add(unixMatcher.group());
        // Match Windows paths
        var winMatcher = WINDOWS_PATH_PATTERN.matcher(message);
        while (winMatcher.find()) paths.add(winMatcher.group());
        return paths;
    }
}
