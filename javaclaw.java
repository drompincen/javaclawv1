///usr/bin/env jbang "$0" "$@" ; exit $?

//JAVA 21+

//REPOS mavenLocal,mavencentral
//DEPS io.github.drompincen.javaclawv1:javaclaw-gateway:0.1.0-SNAPSHOT

package javaclaw;

// ============================================================================
// JavaClaw — Thin JBang launcher
// ============================================================================
// Usage:
//   jbang javaclaw.java                          # Default: starts on port 8080
//   jbang javaclaw.java --headless               # No UI, agent + REST gateway only
//   jbang javaclaw.java --port 9090              # Custom HTTP port (default: 8080)
//   jbang javaclaw.java --testmode               # Test mode with deterministic LLM
//   jbang javaclaw.java --scenario file.json      # Scenario-based E2E test (implies --testmode)
//   jbang javaclaw.java --api-key sk-ant-...      # Set API key (auto-detects Anthropic/OpenAI)
//   jbang javaclaw.java --mongo mongodb://host:port/db   # Custom MongoDB URI
//
// Port override (pick one):
//   1. --port <port>                             CLI argument
//   2. JAVACLAW_PORT=9090                        Environment variable
//   3. -Dserver.port=9090                        System property
//   4. Default: 8080
//
// MongoDB URI resolution order:
//   1. --mongo CLI arg
//   2. JAVACLAW_MONGO_URI env var
//   3. spring.data.mongodb.uri system property
//   4. Default: mongodb://localhost:27017/javaclaw?replicaSet=rs0
//
// Prerequisites:
//   1. Install JBang:  https://www.jbang.dev/download/
//   2. MongoDB running as replica set, or Docker installed (auto-launch)
//   3. Run `mvnw install -DskipTests` first so submodule jars are in ~/.m2
// ============================================================================

import io.github.drompincen.javaclawv1.gateway.JavaClawApplication;
import org.springframework.boot.SpringApplication;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.Socket;
import java.net.URI;
import java.util.List;

public class javaclaw {

    static String mongoUri;
    static boolean headless = false;
    static int port = 8080;

    public static void main(String... args) {
        parseArgs(args);

        System.setProperty("spring.application.name", "javaclaw");
        System.setProperty("spring.data.mongodb.uri", mongoUri);
        System.setProperty("spring.data.mongodb.database", "javaclaw");
        System.setProperty("server.port", String.valueOf(port));

        URI uri = URI.create(mongoUri.replace("mongodb://", "http://").split("\\?")[0]);
        String host = uri.getHost() != null ? uri.getHost() : "localhost";
        int mongoPort = uri.getPort() > 0 ? uri.getPort() : 27017;

        ensureMongo(host, mongoPort);

        System.out.println("  Starting on port " + port + " ...\n");
        SpringApplication.run(JavaClawApplication.class, args);

        // Detect local IP for clickable URL
        String localIp = "localhost";
        try {
            java.net.InetAddress addr = java.net.InetAddress.getLocalHost();
            localIp = addr.getHostAddress();
        } catch (Exception ignored) {}

        System.out.println("\n  ========================================");
        System.out.println("  JavaClaw is ready!");
        System.out.println("  Web UI: http://localhost:" + port + "/index.html");
        if (!localIp.equals("localhost") && !localIp.equals("127.0.0.1")) {
            System.out.println("  Web UI: http://" + localIp + ":" + port + "/index.html");
        }
        System.out.println("  API:    http://localhost:" + port + "/api");
        System.out.println("  ========================================\n");

        if (!headless) {
            launchUI();
        }
    }

    static void parseArgs(String[] args) {
        // Resolve MongoDB URI: CLI > env > sysprop > default
        mongoUri = System.getProperty("spring.data.mongodb.uri",
                System.getenv().getOrDefault("JAVACLAW_MONGO_URI",
                        "mongodb://localhost:27017/javaclaw?replicaSet=rs0"));

        // Resolve port: CLI > env > sysprop > default (8080)
        String envPort = System.getenv("JAVACLAW_PORT");
        if (envPort != null) port = Integer.parseInt(envPort);
        String sysPropPort = System.getProperty("server.port");
        if (sysPropPort != null) port = Integer.parseInt(sysPropPort);

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--headless" -> headless = true;
                case "--testmode" -> System.setProperty("javaclaw.llm.provider", "test");
                case "--scenario" -> {
                    if (i + 1 < args.length) {
                        String scenarioArg = args[++i];
                        // Accumulate multiple --scenario flags into comma-separated list
                        String existing = System.getProperty("javaclaw.scenario.files");
                        if (existing != null && !existing.isBlank()) {
                            System.setProperty("javaclaw.scenario.files", existing + "," + scenarioArg);
                        } else {
                            System.setProperty("javaclaw.scenario.files", scenarioArg);
                        }
                        // Set single-file property for @ConditionalOnProperty activation
                        System.setProperty("javaclaw.scenario.file", scenarioArg);
                        System.setProperty("javaclaw.llm.provider", "test"); // scenario implies test mode
                    }
                }
                case "--api-key" -> {
                    if (i + 1 < args.length) {
                        String key = args[++i];
                        if (key.startsWith("sk-ant-")) {
                            System.setProperty("spring.ai.anthropic.api-key", key);
                            System.out.println("  Anthropic API key set.");
                        } else if (key.startsWith("sk-")) {
                            System.setProperty("spring.ai.openai.api-key", key);
                            System.out.println("  OpenAI API key set.");
                        } else {
                            // Default to OpenAI
                            System.setProperty("spring.ai.openai.api-key", key);
                            System.out.println("  API key set (defaulting to OpenAI).");
                        }
                    }
                }
                case "--mongo" -> { if (i + 1 < args.length) mongoUri = args[++i]; }
                case "--port" -> { if (i + 1 < args.length) port = Integer.parseInt(args[++i]); }
            }
        }
    }

    // -----------------------------------------------------------------------
    // UI launcher
    // -----------------------------------------------------------------------

    static void launchUI() {
        try {
            String userDir = System.getProperty("user.dir");
            java.io.File uiFile = new java.io.File(userDir, "javaclawui.java");
            if (!uiFile.exists()) {
                System.out.println("  javaclawui.java not found in " + userDir);
                System.out.println("  For desktop UI, run: jbang javaclawui.java --url http://localhost:" + port);
                return;
            }
            System.out.println("  Launching UI ...");
            String jbangCmd = System.getProperty("os.name").toLowerCase().contains("win") ? "jbang.cmd" : "jbang";
            new ProcessBuilder(jbangCmd, uiFile.getAbsolutePath(), "--url", "http://localhost:" + port)
                    .inheritIO()
                    .start();
        } catch (Exception e) {
            System.out.println("  Failed to launch UI: " + e.getMessage());
            System.out.println("  Run manually: jbang javaclawui.java --url http://localhost:" + port);
        }
    }

    // -----------------------------------------------------------------------
    // MongoDB auto-detect & Docker launch
    // -----------------------------------------------------------------------

    static final String CONTAINER_NAME = "javaclaw-mongo";

    static void ensureMongo(String host, int mongoPort) {
        System.out.println("\n  Checking MongoDB on " + host + ":" + mongoPort + " ...");

        if (isPortOpen(host, mongoPort)) {
            System.out.println("  MongoDB is already running.\n");
            return;
        }

        if (!host.equals("localhost") && !host.equals("127.0.0.1")) {
            System.err.println("  MongoDB not reachable at " + host + ":" + mongoPort);
            System.err.println("  Cannot auto-launch Docker for remote hosts.");
            System.err.println("  Please ensure MongoDB is running at the specified URI.");
            System.exit(1);
        }

        System.out.println("  MongoDB not reachable. Attempting to start via Docker...");

        if (!isCommandAvailable("docker")) {
            System.err.println("""
                  Docker is not installed or not in PATH.
                   Install Docker Desktop: https://www.docker.com/products/docker-desktop/
                   Or start MongoDB manually on port %d.
                """.formatted(mongoPort));
            System.exit(1);
        }

        if (containerExists(CONTAINER_NAME)) {
            System.out.println("   Found stopped container '" + CONTAINER_NAME + "', restarting...");
            exec("docker", "start", CONTAINER_NAME);
        } else {
            System.out.println("   Creating container '" + CONTAINER_NAME + "'...");
            exec("docker", "run", "-d",
                "--name", CONTAINER_NAME,
                "-p", mongoPort + ":27017",
                "-v", "javaclaw-mongo-data:/data/db",
                "mongo:7",
                "mongod", "--replSet", "rs0", "--bind_ip_all");
        }

        System.out.print("   Waiting for MongoDB to start ");
        boolean ready = false;
        for (int i = 0; i < 30; i++) {
            sleep(1000);
            System.out.print(".");
            if (isPortOpen(host, mongoPort)) { ready = true; break; }
        }
        System.out.println();

        if (!ready) {
            System.err.println("  MongoDB did not start within 30 seconds. Check: docker logs " + CONTAINER_NAME);
            System.exit(1);
        }

        sleep(2000);
        System.out.println("   Initializing replica set...");
        exec("docker", "exec", CONTAINER_NAME, "mongosh", "--quiet", "--eval",
            "try { rs.status() } catch(e) { rs.initiate({_id:'rs0', members:[{_id:0, host:'localhost:27017'}]}) }");

        sleep(2000);
        System.out.println("  MongoDB is running in Docker container '" + CONTAINER_NAME + "'.\n");
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    static boolean isPortOpen(String host, int port) {
        try (Socket s = new Socket(host, port)) { return true; }
        catch (Exception e) { return false; }
    }

    static boolean isCommandAvailable(String cmd) {
        try {
            Process p = new ProcessBuilder(isWindows() ? List.of("where", cmd) : List.of("which", cmd))
                .redirectErrorStream(true).start();
            return p.waitFor() == 0;
        } catch (Exception e) { return false; }
    }

    static boolean containerExists(String name) {
        try {
            Process p = new ProcessBuilder("docker", "ps", "-a", "--filter", "name=^/" + name + "$", "--format", "{{.Names}}")
                .redirectErrorStream(true).start();
            String output = new String(p.getInputStream().readAllBytes()).trim();
            p.waitFor();
            return name.equals(output);
        } catch (Exception e) { return false; }
    }

    static void exec(String... cmd) {
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
            Process p = pb.start();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = br.readLine()) != null) System.out.println("   " + line);
            }
            int exit = p.waitFor();
            if (exit != 0) System.err.println("   Command exited with code " + exit + ": " + String.join(" ", cmd));
        } catch (Exception e) {
            System.err.println("   Failed to run: " + String.join(" ", cmd) + " - " + e.getMessage());
        }
    }

    static boolean isWindows() { return System.getProperty("os.name", "").toLowerCase().contains("win"); }
    static void sleep(long ms) { try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } }
}
