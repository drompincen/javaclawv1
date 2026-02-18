package io.github.drompincen.javaclawv1.runtime.agent.graph;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * E2E code execution tests: write a time util in Java/Python,
 * execute it via ProcessBuilder (mirroring JBangExecTool / PythonExecTool),
 * and assert the output time matches the system clock.
 *
 * These tests validate the full pipeline: write file → execute → parse output.
 * They skip gracefully if JBang or Python are not installed.
 *
 * On Windows with WSL, Python is detected via {@code wsl python3} since the
 * Windows App Execution Aliases (Microsoft Store stubs) are not real Python.
 */
class CodeExecutionTest {

    private static final boolean IS_WINDOWS =
            System.getProperty("os.name", "").toLowerCase().contains("win");

    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private static final Pattern TIME_PATTERN =
            Pattern.compile("CURRENT_TIME=(\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2})");

    private final List<Path> tempFiles = new ArrayList<>();

    @AfterEach
    void cleanup() {
        for (Path p : tempFiles) {
            try { Files.deleteIfExists(p); } catch (Exception ignored) {}
        }
    }

    // ---------------------------------------------------------------
    // Java execution via JBang
    // ---------------------------------------------------------------

    @Test
    @EnabledIf("isJBangAvailable")
    void javaTimeUtil_writesAndExecutes_outputMatchesClock() throws Exception {
        // JBang/javac requires filename to match public class name exactly
        Path tmpDir = Path.of(System.getProperty("java.io.tmpdir"));
        Path javaFile = tmpDir.resolve("JavaClawTimeTest.java");
        tempFiles.add(javaFile);

        String javaSource = """
                import java.time.LocalDateTime;
                import java.time.format.DateTimeFormatter;

                public class JavaClawTimeTest {
                    public static void main(String[] args) {
                        LocalDateTime now = LocalDateTime.now();
                        String formatted = now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
                        System.out.println("CURRENT_TIME=" + formatted);
                    }
                }
                """;

        // Step 1: Write the file (mirrors WriteFileTool)
        Files.writeString(javaFile, javaSource);
        assertThat(javaFile).exists().isNotEmptyFile();

        // Step 2: Execute via JBang (mirrors JBangExecTool)
        String jbangCmd = IS_WINDOWS ? "jbang.cmd" : "jbang";
        ProcessBuilder pb = new ProcessBuilder(jbangCmd, javaFile.toAbsolutePath().toString());
        pb.redirectErrorStream(true);

        LocalDateTime beforeExec = LocalDateTime.now();
        Process proc = pb.start();
        String stdout = captureOutput(proc);
        boolean finished = proc.waitFor(60, TimeUnit.SECONDS);

        assertThat(finished).as("JBang process should finish within 60s").isTrue();
        assertThat(proc.exitValue()).as("JBang exit code (output: %s)", stdout.trim()).isZero();

        // Step 3: Assert output contains CURRENT_TIME=yyyy-MM-dd HH:mm
        assertThat(stdout).contains("CURRENT_TIME=");

        Matcher m = TIME_PATTERN.matcher(stdout);
        assertThat(m.find()).as("Output should match CURRENT_TIME=yyyy-MM-dd HH:mm pattern").isTrue();

        // Step 4: Assert the time is within 2 minutes of now
        LocalDateTime parsed = LocalDateTime.parse(m.group(1), TIME_FMT);
        long minutesDiff = Math.abs(ChronoUnit.MINUTES.between(beforeExec, parsed));
        assertThat(minutesDiff).as("Parsed time should be within 2 minutes of system clock")
                .isLessThanOrEqualTo(2);
    }

    // ---------------------------------------------------------------
    // Python execution
    // ---------------------------------------------------------------

    @Test
    @EnabledIf("isPythonAvailable")
    void pythonTimeUtil_writesAndExecutes_outputMatchesClock() throws Exception {
        Path pyFile = tempFile("javaclaw_time_test", ".py");

        String pySource = """
                from datetime import datetime
                now = datetime.now()
                print(f"CURRENT_TIME={now.strftime('%Y-%m-%d %H:%M')}")
                """;

        // Step 1: Write the file (mirrors WriteFileTool)
        Files.writeString(pyFile, pySource);
        assertThat(pyFile).exists().isNotEmptyFile();

        // Step 2: Execute via Python (mirrors PythonExecTool)
        // On Windows with WSL, use "wsl python3" with translated path
        PythonCommand pythonCommand = detectPython();
        String filePath = pyFile.toAbsolutePath().toString();
        List<String> command;
        if (pythonCommand.isWsl()) {
            command = List.of("wsl", pythonCommand.command(), toWslPath(filePath));
        } else {
            command = List.of(pythonCommand.command(), filePath);
        }
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);

        LocalDateTime beforeExec = LocalDateTime.now();
        Process proc = pb.start();
        String stdout = captureOutput(proc);
        boolean finished = proc.waitFor(30, TimeUnit.SECONDS);

        assertThat(finished).as("Python process should finish within 30s").isTrue();
        assertThat(proc.exitValue()).as("Python exit code (output: %s)", stdout.trim()).isZero();

        // Step 3: Assert output
        assertThat(stdout).contains("CURRENT_TIME=");

        Matcher m = TIME_PATTERN.matcher(stdout);
        assertThat(m.find()).as("Output should match CURRENT_TIME=yyyy-MM-dd HH:mm pattern").isTrue();

        // Step 4: Assert the time is within 2 minutes
        LocalDateTime parsed = LocalDateTime.parse(m.group(1), TIME_FMT);
        long minutesDiff = Math.abs(ChronoUnit.MINUTES.between(beforeExec, parsed));
        assertThat(minutesDiff).as("Parsed time should be within 2 minutes of system clock")
                .isLessThanOrEqualTo(2);
    }

    // ---------------------------------------------------------------
    // Condition methods for @EnabledIf
    // ---------------------------------------------------------------

    static boolean isJBangAvailable() {
        return isCommandAvailable(IS_WINDOWS ? "jbang.cmd" : "jbang", "--version");
    }

    static boolean isPythonAvailable() {
        return detectPython() != null;
    }

    // ---------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------

    /** Wraps the detected python command and whether it runs via WSL. */
    record PythonCommand(String command, boolean isWsl) {}

    /**
     * Detect an available Python command.
     *
     * On Windows: tries native python/python3 first (checking they're real, not
     * Microsoft Store stubs), then falls back to {@code wsl python3}.
     * On Linux/macOS: tries python3, then python.
     */
    private static PythonCommand detectPython() {
        if (IS_WINDOWS) {
            // Try native Windows Python first (skip MS Store stubs by checking exit code AND output)
            for (String cmd : new String[]{"python", "python3"}) {
                if (isRealPython(cmd)) {
                    return new PythonCommand(cmd, false);
                }
            }
            // Fall back to WSL Python
            if (isCommandAvailable("wsl", "python3", "--version")) {
                return new PythonCommand("python3", true);
            }
            return null;
        }
        // Linux / macOS
        for (String cmd : new String[]{"python3", "python"}) {
            if (isCommandAvailable(cmd, "--version")) {
                return new PythonCommand(cmd, false);
            }
        }
        return null;
    }

    /**
     * Check if a python command is real (not the Windows MS Store alias stub).
     * The stub exits with code 9009 and prints a "not found" message.
     */
    private static boolean isRealPython(String cmd) {
        try {
            Process p = new ProcessBuilder(cmd, "--version")
                    .redirectErrorStream(true)
                    .start();
            String output = captureOutput(p);
            boolean done = p.waitFor(5, TimeUnit.SECONDS);
            if (!done) { p.destroyForcibly(); return false; }
            // Real Python prints "Python X.Y.Z" and exits 0
            return p.exitValue() == 0 && output.trim().startsWith("Python ");
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Convert a Windows path like {@code C:\Users\drom\...\file.py}
     * to a WSL path like {@code /mnt/c/Users/drom/.../file.py}.
     */
    static String toWslPath(String windowsPath) {
        if (windowsPath.length() >= 2 && windowsPath.charAt(1) == ':') {
            char drive = Character.toLowerCase(windowsPath.charAt(0));
            String rest = windowsPath.substring(2).replace('\\', '/');
            return "/mnt/" + drive + rest;
        }
        return windowsPath.replace('\\', '/');
    }

    private Path tempFile(String prefix, String suffix) throws Exception {
        Path tmp = Files.createTempFile(prefix, suffix);
        tempFiles.add(tmp);
        return tmp;
    }

    private static String captureOutput(Process proc) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line).append('\n');
            }
        }
        return sb.toString();
    }

    private static boolean isCommandAvailable(String... command) {
        try {
            Process p = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start();
            boolean done = p.waitFor(5, TimeUnit.SECONDS);
            if (!done) { p.destroyForcibly(); return false; }
            return p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
