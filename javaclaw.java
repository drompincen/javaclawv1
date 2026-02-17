///usr/bin/env jbang "$0" "$@" ; exit $?

//JAVA 21+

//DEPS org.springframework.boot:spring-boot-starter-web:3.2.5
//DEPS org.springframework.boot:spring-boot-starter-websocket:3.2.5
//DEPS org.springframework.boot:spring-boot-starter-data-mongodb:3.2.5
//DEPS org.springframework.boot:spring-boot-starter-data-mongodb-reactive:3.2.5
//DEPS com.fasterxml.jackson.core:jackson-databind:2.17.0
//DEPS com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.17.0
//DEPS org.apache.poi:poi-ooxml:5.2.5

package javaclaw;

// ============================================================================
// JavaClaw v2 — Single-file JBang runner
// ============================================================================
// Usage:
//   jbang javaclaw.java                          # Default: starts on port 8080
//   jbang javaclaw.java --headless               # No UI, agent + REST gateway only
//   jbang javaclaw.java --port 9090              # Custom HTTP port (default: 8080)
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
// ============================================================================

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.IndexDirection;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.index.TextIndexed;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.config.EnableMongoRepositories;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.Socket;
import java.net.URI;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

// ---------------------------------------------------------------------------
// Main Application
// ---------------------------------------------------------------------------

@SpringBootApplication
@EnableMongoRepositories(considerNestedRepositories = true)
@EnableWebSocket
public class javaclaw implements WebSocketConfigurer {

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
        SpringApplication.run(javaclaw.class, args);

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
            // On Windows, ProcessBuilder needs jbang.cmd; on Unix, jbang works directly
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

        // Only auto-launch Docker for localhost
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

    private final JavaClawWsHandler wsHandler;

    public javaclaw(JavaClawWsHandler wsHandler) { this.wsHandler = wsHandler; }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(wsHandler, "/ws").setAllowedOrigins("*");
    }

    @Configuration
    static class JacksonConfig {
        @org.springframework.context.annotation.Bean
        ObjectMapper objectMapper() {
            ObjectMapper mapper = new ObjectMapper();
            mapper.findAndRegisterModules();
            mapper.setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY);
            mapper.configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);
            return mapper;
        }
    }

    // =======================================================================
    // ENUMS
    // =======================================================================

    enum SessionStatus { IDLE, RUNNING, PAUSED, FAILED, COMPLETED }

    enum EventType {
        USER_MESSAGE_RECEIVED, AGENT_STEP_STARTED, MODEL_TOKEN_DELTA,
        TOOL_CALL_PROPOSED, TOOL_CALL_APPROVED, TOOL_CALL_DENIED,
        TOOL_CALL_STARTED, TOOL_STDOUT_DELTA, TOOL_STDERR_DELTA,
        TOOL_PROGRESS, TOOL_RESULT, AGENT_STEP_COMPLETED,
        CHECKPOINT_CREATED, SESSION_STATUS_CHANGED, ERROR,
        TICKET_CREATED, TICKET_UPDATED, IDEA_PROMOTED,
        REMINDER_TRIGGERED, APPROVAL_REQUESTED, APPROVAL_RESPONDED,
        RESOURCE_ASSIGNED, MEMORY_STORED, MEMORY_RECALLED, MEMORY_DISTILLED,
        SEARCH_REQUESTED, SEARCH_RESPONSE_SUBMITTED, AGENT_DELEGATED, AGENT_SWITCHED,
        AGENT_CHECK_REQUESTED, AGENT_CHECK_PASSED, AGENT_CHECK_FAILED, AGENT_RESPONSE
    }

    enum ToolRiskProfile { READ_ONLY, WRITE_FILES, EXEC_SHELL, BROWSER_CONTROL, NETWORK_CALLS }

    enum AgentRole { CONTROLLER, SPECIALIST, CHECKER }

    enum MemoryScope { GLOBAL, PROJECT, SESSION, THREAD }

    enum TicketPriority { LOW, MEDIUM, HIGH, CRITICAL }

    enum TicketStatus { OPEN, IN_PROGRESS, DONE, CLOSED }

    // =======================================================================
    // RECORDS (DTOs)
    // =======================================================================

    record ModelConfig(String modelName, double temperature, int maxTokens, String systemPrompt) {
        static ModelConfig defaults() { return new ModelConfig("claude-sonnet-4-5-20250929", 0.7, 4096, null); }
    }

    record ToolPolicy(List<String> allowList, List<String> denyList, Map<String, String> approvalOverrides) {
        static ToolPolicy allowAll() { return new ToolPolicy(List.of("*"), List.of(), Map.of()); }
    }

    record CreateSessionRequest(ModelConfig modelConfig, ToolPolicy toolPolicy, Map<String, String> metadata, String threadId) {}
    record SendMessageRequest(String content, String role) {}
    record CreateSpecRequest(String title, List<String> tags, String source, Object jsonBody) {}
    record CreateProjectRequest(String name, String description) {}
    record CreateThreadRequest(String title) {}
    record CreateIdeaRequest(String title, String content) {}
    record CreateTicketRequest(String title, String description, String priority) {}
    record CreateDesignRequest(String title, String source) {}
    record CreateReminderRequest(String sessionId, String message, boolean recurring, Long intervalMs, String triggerAt) {}
    record SetApiKeysRequest(String anthropicKey, String openaiKey) {}
    record SubmitSearchResponseRequest(String sessionId, String requestId, String content) {}

    // =======================================================================
    // MONGO DOCUMENTS
    // =======================================================================

    @Document(collection = "sessions")
    static class SessionDoc {
        @Id String sessionId;
        @Indexed String threadId;
        Instant createdAt;
        @Indexed(direction = IndexDirection.DESCENDING) Instant updatedAt;
        SessionStatus status;
        ModelConfig modelConfig;
        ToolPolicy toolPolicy;
        String currentCheckpointId;
        Map<String, String> metadata;
    }

    @Document(collection = "messages")
    @CompoundIndex(name = "session_seq", def = "{'sessionId': 1, 'seq': 1}", unique = true)
    static class MessageDoc {
        @Id String messageId;
        String sessionId;
        long seq;
        String role;
        String content;
        Instant timestamp;
        // Response metadata — populated for assistant messages
        String agentId;          // which agent produced this (controller, coder, pm, file_tool, etc.)
        String apiProvider;      // "anthropic", "openai", or "mock"
        Long durationMs;         // how long the agent call took
        boolean mocked;          // true if this was a mock/simulated response
    }

    @Document(collection = "events")
    @CompoundIndex(name = "session_seq", def = "{'sessionId': 1, 'seq': 1}", unique = true)
    static class EventDoc {
        @Id String eventId;
        String sessionId;
        long seq;
        EventType type;
        Object payload;
        Instant timestamp;
    }

    @Document(collection = "checkpoints")
    @CompoundIndex(name = "session_step", def = "{'sessionId': 1, 'stepNo': -1}")
    static class CheckpointDoc {
        @Id String checkpointId;
        String sessionId;
        int stepNo;
        Instant createdAt;
        Object state;
        long eventOffset;
    }

    @Document(collection = "specs")
    static class SpecDoc {
        @Id String specId;
        @TextIndexed String title;
        @Indexed List<String> tags;
        String source;
        Object jsonBody;
        Instant createdAt;
        Instant updatedAt;
        @Version int version;
    }

    @Document(collection = "locks")
    static class LockDoc {
        @Id String lockId;
        String sessionId;
        String owner;
        @Indexed(expireAfterSeconds = 60) Instant expiresAt;
        Instant acquiredAt;
    }

    @Document(collection = "projects")
    static class ProjectDoc {
        @Id String projectId;
        String name;
        String description;
        Instant createdAt;
        @Indexed(direction = IndexDirection.DESCENDING) Instant updatedAt;
    }

    @Document(collection = "threads")
    static class ThreadDoc {
        @Id String threadId;
        @Deprecated String projectId;
        @Indexed List<String> projectIds;
        String title;
        SessionStatus status;
        Instant createdAt;
        @Indexed(direction = IndexDirection.DESCENDING) Instant updatedAt;

        List<String> getEffectiveProjectIds() {
            if (projectIds != null && !projectIds.isEmpty()) return projectIds;
            if (projectId != null) return List.of(projectId);
            return List.of();
        }
    }

    @Document(collection = "agents")
    static class AgentDoc {
        @Id String agentId;
        String name;
        AgentRole role;
        String description;
        List<String> skills;
        Instant createdAt;
    }

    @Document(collection = "ideas")
    static class IdeaDoc {
        @Id String ideaId;
        @Indexed String projectId;
        String title;
        String content;
        boolean promoted;
        String promotedTicketId;
        Instant createdAt;
    }

    @Document(collection = "tickets")
    static class TicketDoc {
        @Id String ticketId;
        @Indexed String projectId;
        String title;
        String description;
        TicketPriority priority;
        TicketStatus status;
        Instant createdAt;
        Instant updatedAt;
    }

    @Document(collection = "designs")
    static class DesignDoc {
        @Id String designId;
        @Indexed String projectId;
        String title;
        String source;
        Instant createdAt;
    }

    @Document(collection = "reminders")
    static class ReminderDoc {
        @Id String reminderId;
        String sessionId;
        String message;
        boolean recurring;
        Long intervalMs;
        String triggerAt;
        Instant createdAt;
    }

    @Document(collection = "memories")
    @CompoundIndex(name = "scope_key", def = "{'scope': 1, 'key': 1}")
    @CompoundIndex(name = "thread_scope", def = "{'threadId': 1, 'scope': 1}")
    static class MemoryDoc {
        @Id String memoryId;
        MemoryScope scope;
        String projectId;
        String sessionId;
        String threadId;
        String key;
        String content;
        List<String> tags;
        String createdBy;
        Instant createdAt;
        Instant updatedAt;
    }

    @Document(collection = "resources")
    static class ResourceDoc {
        @Id String resourceId;
        String name;
        String email;
        String role; // ENGINEER, DESIGNER, PM, QA
        List<String> skills;
        double availability; // 0.0 to 1.0
        @Indexed String projectId; // optional: link to a project
        Instant createdAt;
        Instant updatedAt;
    }

    // =======================================================================
    // REPOSITORIES
    // =======================================================================

    interface SessionRepo extends MongoRepository<SessionDoc, String> {
        List<SessionDoc> findAllByOrderByUpdatedAtDesc();
        List<SessionDoc> findByThreadId(String threadId);
        List<SessionDoc> findByThreadIdIsNullOrderByUpdatedAtDesc();
    }

    interface MessageRepo extends MongoRepository<MessageDoc, String> {
        List<MessageDoc> findBySessionIdOrderBySeqAsc(String sessionId);
        long countBySessionId(String sessionId);
        void deleteBySessionId(String sessionId);
    }

    interface EventRepo extends MongoRepository<EventDoc, String> {
        Optional<EventDoc> findTopBySessionIdOrderBySeqDesc(String sessionId);
    }

    interface CheckpointRepo extends MongoRepository<CheckpointDoc, String> {
        Optional<CheckpointDoc> findTopBySessionIdOrderByStepNoDesc(String sessionId);
    }

    interface SpecRepo extends MongoRepository<SpecDoc, String> {
        List<SpecDoc> findByTagsContaining(String tag);
        @org.springframework.data.mongodb.repository.Query("{ '$text': { '$search': ?0 } }")
        List<SpecDoc> searchByText(String query);
        List<SpecDoc> findByTagsContainingAndTitleContainingIgnoreCase(String tag, String q);
    }

    interface LockRepo extends MongoRepository<LockDoc, String> {
        Optional<LockDoc> findBySessionId(String sessionId);
        void deleteBySessionId(String sessionId);
    }

    interface ProjectRepo extends MongoRepository<ProjectDoc, String> {
        List<ProjectDoc> findAllByOrderByUpdatedAtDesc();
        Optional<ProjectDoc> findByNameIgnoreCase(String name);
    }

    interface ThreadRepo extends MongoRepository<ThreadDoc, String> {
        List<ThreadDoc> findByProjectIdsOrderByUpdatedAtDesc(String projectId);
        Optional<ThreadDoc> findByTitleIgnoreCaseAndProjectIdsContaining(String title, String projectId);
    }

    interface AgentRepo extends MongoRepository<AgentDoc, String> {}

    interface IdeaRepo extends MongoRepository<IdeaDoc, String> {
        List<IdeaDoc> findByProjectIdOrderByCreatedAtDesc(String projectId);
    }

    interface TicketRepo extends MongoRepository<TicketDoc, String> {
        List<TicketDoc> findByProjectIdOrderByCreatedAtDesc(String projectId);
    }

    interface DesignRepo extends MongoRepository<DesignDoc, String> {
        List<DesignDoc> findByProjectIdOrderByCreatedAtDesc(String projectId);
    }

    interface ReminderRepo extends MongoRepository<ReminderDoc, String> {
        List<ReminderDoc> findBySessionId(String sessionId);
    }

    interface MemoryRepo extends MongoRepository<MemoryDoc, String> {
        List<MemoryDoc> findByScope(MemoryScope scope);
        List<MemoryDoc> findByScopeAndProjectId(MemoryScope scope, String projectId);
        List<MemoryDoc> findByScopeAndSessionId(MemoryScope scope, String sessionId);
        List<MemoryDoc> findByScopeAndThreadId(MemoryScope scope, String threadId);
        Optional<MemoryDoc> findByScopeAndKey(MemoryScope scope, String key);
        Optional<MemoryDoc> findByScopeAndThreadIdAndKey(MemoryScope scope, String threadId, String key);
        @org.springframework.data.mongodb.repository.Query("{'content': {$regex: ?0, $options: 'i'}}")
        List<MemoryDoc> searchContent(String query);
    }

    interface ResourceRepo extends MongoRepository<ResourceDoc, String> {
        List<ResourceDoc> findByProjectId(String projectId);
    }

    // =======================================================================
    // EVENT SERVICE
    // =======================================================================

    @Service
    static class EventService {
        private final EventRepo eventRepo;
        private final ConcurrentHashMap<String, AtomicLong> seqCounters = new ConcurrentHashMap<>();

        EventService(EventRepo eventRepo) { this.eventRepo = eventRepo; }

        EventDoc emit(String sessionId, EventType type, Object payload) {
            long seq = seqCounters
                .computeIfAbsent(sessionId, k -> {
                    long last = eventRepo.findTopBySessionIdOrderBySeqDesc(sessionId)
                        .map(e -> e.seq).orElse(0L);
                    return new AtomicLong(last);
                }).incrementAndGet();

            EventDoc e = new EventDoc();
            e.eventId = UUID.randomUUID().toString();
            e.sessionId = sessionId;
            e.seq = seq;
            e.type = type;
            e.payload = payload;
            e.timestamp = Instant.now();
            return eventRepo.save(e);
        }

        EventDoc emit(String sessionId, EventType type) { return emit(sessionId, type, null); }
    }

    // =======================================================================
    // CHANGE STREAM TAILER (with polling fallback)
    // =======================================================================

    interface EventStreamListener { void onEvent(EventDoc event); }

    @Component
    static class EventTailer {
        private final MongoTemplate mongo;
        private final List<EventStreamListener> listeners = new CopyOnWriteArrayList<>();
        private final ExecutorService exec = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "event-tailer"); t.setDaemon(true); return t;
        });
        private volatile boolean running = false;

        EventTailer(MongoTemplate mongo) { this.mongo = mongo; }
        void addListener(EventStreamListener l) { listeners.add(l); }

        @PostConstruct void start() { running = true; exec.submit(this::run); }
        @PreDestroy void stop() { running = false; exec.shutdownNow(); }

        private void run() {
            while (running) {
                try { doChangeStream(); }
                catch (Exception e) { if (running) pollFallback(); }
            }
        }

        private void doChangeStream() {
            var coll = mongo.getDb().getCollection("events");
            var stream = coll.watch(List.of(
                com.mongodb.client.model.Aggregates.match(
                    com.mongodb.client.model.Filters.eq("operationType", "insert"))
            )).fullDocument(com.mongodb.client.model.changestream.FullDocument.UPDATE_LOOKUP);

            try (var cursor = stream.iterator()) {
                while (running && cursor.hasNext()) {
                    var change = cursor.next();
                    var doc = change.getFullDocument();
                    if (doc != null) {
                        EventDoc event = mongo.getConverter().read(EventDoc.class, doc);
                        listeners.forEach(l -> l.onEvent(event));
                    }
                }
            }
        }

        private void pollFallback() {
            long lastSeq = -1;
            while (running) {
                try {
                    var events = mongo.find(
                        Query.query(Criteria.where("seq").gt(lastSeq)).with(Sort.by("seq")),
                        EventDoc.class);
                    for (var e : events) { listeners.forEach(l -> l.onEvent(e)); lastSeq = e.seq; }
                    Thread.sleep(200);
                } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
                  catch (Exception ignored) {
                    try { Thread.sleep(1000); } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt(); break;
                    }
                }
            }
        }
    }

    // =======================================================================
    // LOCK SERVICE + CHECKPOINT SERVICE
    // =======================================================================

    @Service
    static class LockService {
        private final LockRepo lockRepo;
        LockService(LockRepo lockRepo) { this.lockRepo = lockRepo; }

        Optional<String> tryAcquire(String sessionId) {
            var existing = lockRepo.findBySessionId(sessionId);
            if (existing.isPresent() && existing.get().expiresAt.isAfter(Instant.now())) return Optional.empty();
            existing.ifPresent(lockRepo::delete);
            String owner = UUID.randomUUID().toString();
            LockDoc lock = new LockDoc();
            lock.lockId = UUID.randomUUID().toString(); lock.sessionId = sessionId;
            lock.owner = owner; lock.acquiredAt = Instant.now();
            lock.expiresAt = Instant.now().plus(60, ChronoUnit.SECONDS);
            lockRepo.save(lock);
            return Optional.of(owner);
        }

        boolean renew(String sessionId, String owner) {
            return lockRepo.findBySessionId(sessionId)
                .filter(l -> l.owner.equals(owner))
                .map(l -> { l.expiresAt = Instant.now().plus(60, ChronoUnit.SECONDS); lockRepo.save(l); return true; })
                .orElse(false);
        }

        void release(String sessionId, String owner) {
            lockRepo.findBySessionId(sessionId).filter(l -> l.owner.equals(owner)).ifPresent(lockRepo::delete);
        }
    }

    @Service
    static class CheckpointService {
        private final CheckpointRepo cpRepo;
        private final EventRepo eventRepo;

        CheckpointService(CheckpointRepo cpRepo, EventRepo eventRepo) {
            this.cpRepo = cpRepo; this.eventRepo = eventRepo;
        }

        CheckpointDoc create(String sessionId, int stepNo, Object state) {
            long offset = eventRepo.findTopBySessionIdOrderBySeqDesc(sessionId).map(e -> e.seq).orElse(0L);
            CheckpointDoc cp = new CheckpointDoc();
            cp.checkpointId = UUID.randomUUID().toString(); cp.sessionId = sessionId;
            cp.stepNo = stepNo; cp.createdAt = Instant.now(); cp.state = state; cp.eventOffset = offset;
            return cpRepo.save(cp);
        }

        Optional<CheckpointDoc> latest(String sessionId) {
            return cpRepo.findTopBySessionIdOrderByStepNoDesc(sessionId);
        }
    }

    // =======================================================================
    // AGENT BOOTSTRAP
    // =======================================================================

    @Component
    static class AgentBootstrap {
        private final AgentRepo agentRepo;

        AgentBootstrap(AgentRepo agentRepo) { this.agentRepo = agentRepo; }

        @PostConstruct
        void bootstrap() {
            long count = agentRepo.count();
            if (count > 0) {
                List<String> ids = agentRepo.findAll().stream().map(a -> a.agentId).toList();
                System.out.println("  Agents already seeded (" + count + " found), checking for missing agents");
                seedIfMissing(ids, "controller", AgentRole.CONTROLLER, "Routes tasks to specialist agents using LLM",
                        List.of("delegation", "routing", "task_analysis"));
                seedIfMissing(ids, "coder", AgentRole.SPECIALIST, "Handles coding tasks: implementation, debugging, testing",
                        List.of("code_analysis", "code_generation", "debugging", "testing"));
                seedIfMissing(ids, "reviewer", AgentRole.CHECKER, "Reviews specialist output for quality and correctness",
                        List.of("review", "quality_check", "validation"));
                seedIfMissing(ids, "pm", AgentRole.SPECIALIST, "Project management: planning, tickets, milestones, resources",
                        List.of("project_management", "sprint_planning", "ticket_management", "resource_planning"));
                seedIfMissing(ids, "distiller", AgentRole.SPECIALIST, "Distills completed sessions into persistent memories",
                        List.of("memory_extraction", "summarization", "knowledge_distillation"));
                seedIfMissing(ids, "generalist", AgentRole.SPECIALIST, "Handles general questions, life advice, brainstorming",
                        List.of("general_knowledge", "advice", "brainstorming", "writing_help"));
                seedIfMissing(ids, "reminder", AgentRole.SPECIALIST, "Schedules reminders and manages recurring tasks",
                        List.of("reminders", "scheduling", "time_management"));
                return;
            }
            System.out.println("  Seeding default agents...");
            seed("controller", AgentRole.CONTROLLER, "Routes tasks to specialist agents using LLM",
                    List.of("delegation", "routing", "task_analysis"));
            seed("coder", AgentRole.SPECIALIST, "Handles coding tasks: implementation, debugging, testing",
                    List.of("code_analysis", "code_generation", "debugging", "testing"));
            seed("reviewer", AgentRole.CHECKER, "Reviews specialist output for quality and correctness",
                    List.of("review", "quality_check", "validation"));
            seed("pm", AgentRole.SPECIALIST, "Project management: planning, tickets, milestones, resources",
                    List.of("project_management", "sprint_planning", "ticket_management", "resource_planning"));
            seed("distiller", AgentRole.SPECIALIST, "Distills completed sessions into persistent memories",
                    List.of("memory_extraction", "summarization", "knowledge_distillation"));
            seed("generalist", AgentRole.SPECIALIST, "Handles general questions, life advice, brainstorming",
                    List.of("general_knowledge", "advice", "brainstorming", "writing_help"));
            seed("reminder", AgentRole.SPECIALIST, "Schedules reminders and manages recurring tasks",
                    List.of("reminders", "scheduling", "time_management"));
        }

        private void seedIfMissing(List<String> ids, String agentId, AgentRole role, String desc, List<String> skills) {
            if (!ids.contains(agentId)) {
                seed(agentId, role, desc, skills);
                System.out.println("  Seeded missing agent: " + agentId);
            }
        }

        private void seed(String id, AgentRole role, String description, List<String> skills) {
            AgentDoc a = new AgentDoc();
            a.agentId = id;
            a.name = id.substring(0, 1).toUpperCase() + id.substring(1);
            a.role = role;
            a.description = description;
            a.skills = skills;
            a.createdAt = Instant.now();
            agentRepo.save(a);
        }
    }

    // =======================================================================
    // AGENT LOOP (with fake LLM + thread support)
    // =======================================================================

    @Component
    static class AgentLoop {
        private final SessionRepo sessionRepo;
        private final ThreadRepo threadRepo;
        private final ProjectRepo projectRepo;
        private final MessageRepo messageRepo;
        private final EventService eventService;
        private final CheckpointService cpService;
        private final LockService lockService;
        private final ObjectMapper mapper;
        private final ExecutorService exec = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "agent"); t.setDaemon(true); return t;
        });
        private final ConcurrentHashMap<String, Future<?>> running = new ConcurrentHashMap<>();

        private DistillerService distillerService;
        private JiraImportService jiraImportService;
        private LlmClient llmClient;
        private ReminderRepo reminderRepo;

        AgentLoop(SessionRepo sessionRepo, ThreadRepo threadRepo, ProjectRepo projectRepo,
                  MessageRepo messageRepo, EventService eventService, CheckpointService cpService,
                  LockService lockService, ObjectMapper mapper) {
            this.sessionRepo = sessionRepo; this.threadRepo = threadRepo; this.projectRepo = projectRepo;
            this.messageRepo = messageRepo; this.eventService = eventService;
            this.cpService = cpService; this.lockService = lockService; this.mapper = mapper;
        }

        @org.springframework.beans.factory.annotation.Autowired
        void setDistillerService(DistillerService distillerService) {
            this.distillerService = distillerService;
        }

        @org.springframework.beans.factory.annotation.Autowired
        void setJiraImportService(JiraImportService jiraImportService) {
            this.jiraImportService = jiraImportService;
        }

        @org.springframework.beans.factory.annotation.Autowired
        void setLlmClient(LlmClient llmClient) {
            this.llmClient = llmClient;
        }

        @org.springframework.beans.factory.annotation.Autowired
        void setReminderRepo(ReminderRepo reminderRepo) {
            this.reminderRepo = reminderRepo;
        }

        void startAsync(String sessionId) {
            if (running.containsKey(sessionId)) return;
            running.put(sessionId, exec.submit(() -> loop(sessionId)));
        }

        void stop(String sessionId) {
            var f = running.remove(sessionId);
            if (f != null) f.cancel(true);
        }

        private void loop(String sessionId) {
            var lockOwner = lockService.tryAcquire(sessionId);
            if (lockOwner.isEmpty()) return;
            String owner = lockOwner.get();
            long loopStart = System.currentTimeMillis();
            try {
                // Dual lookup: session first, then thread
                boolean isThread = false;
                SessionDoc session = sessionRepo.findById(sessionId).orElse(null);
                if (session == null) {
                    ThreadDoc thread = threadRepo.findById(sessionId).orElse(null);
                    if (thread == null) throw new RuntimeException("Session/Thread not found: " + sessionId);
                    isThread = true;
                    thread.status = SessionStatus.RUNNING;
                    thread.updatedAt = Instant.now();
                    threadRepo.save(thread);
                } else {
                    session.status = SessionStatus.RUNNING;
                    session.updatedAt = Instant.now();
                    sessionRepo.save(session);
                }
                eventService.emit(sessionId, EventType.SESSION_STATUS_CHANGED, Map.of("status", "RUNNING"));

                // Get messages
                var messages = messageRepo.findBySessionIdOrderBySeqAsc(sessionId);
                String lastUserMessage = "";
                for (int i = messages.size() - 1; i >= 0; i--) {
                    if ("user".equals(messages.get(i).role)) {
                        lastUserMessage = messages.get(i).content;
                        break;
                    }
                }

                System.out.printf("  [AgentLoop] session=%s type=%s messages=%d user=\"%s\"%n",
                        sessionId.substring(0, 8), isThread ? "thread" : "session", messages.size(),
                        truncate(lastUserMessage, 80));

                // === Check for context commands (use project, use thread, whereami) ===
                String cmdResponse = handleContextCommand(lastUserMessage.trim(), sessionId, isThread);
                if (cmdResponse != null) {
                    long cmdStart = System.currentTimeMillis();
                    eventService.emit(sessionId, EventType.AGENT_STEP_STARTED,
                            Map.of("step", 1, "agentId", "controller"));
                    streamTokens(sessionId, cmdResponse);
                    long cmdDuration = System.currentTimeMillis() - cmdStart;
                    eventService.emit(sessionId, EventType.AGENT_STEP_COMPLETED,
                            Map.of("step", 1, "agentId", "controller", "durationMs", cmdDuration,
                                    "apiProvider", "mock", "mocked", true));
                    // Save as assistant message
                    long cmdSeq = messageRepo.countBySessionId(sessionId) + 1;
                    MessageDoc cmdMsg = new MessageDoc();
                    cmdMsg.messageId = UUID.randomUUID().toString();
                    cmdMsg.sessionId = sessionId;
                    cmdMsg.seq = cmdSeq;
                    cmdMsg.role = "assistant";
                    cmdMsg.content = cmdResponse;
                    cmdMsg.timestamp = Instant.now();
                    cmdMsg.agentId = "controller";
                    cmdMsg.apiProvider = "mock";
                    cmdMsg.durationMs = cmdDuration;
                    cmdMsg.mocked = true;
                    messageRepo.save(cmdMsg);
                    updateStatus(sessionId, isThread, SessionStatus.COMPLETED);
                    System.out.printf("  [AgentLoop] COMMAND handled session=%s total=%dms%n",
                            sessionId.substring(0, 8), System.currentTimeMillis() - loopStart);
                    return; // Short-circuit — no delegation needed
                }

                // === Step 1 — Controller ===
                long stepStart = System.currentTimeMillis();
                lockService.renew(sessionId, owner);
                eventService.emit(sessionId, EventType.AGENT_STEP_STARTED,
                        Map.of("step", 1, "agentId", "controller"));
                String delegate = determineDelegate(lastUserMessage, sessionId);
                boolean controllerUsedLlm = llmClient != null && llmClient.isAvailable()
                        && !"jira_import".equals(delegate) && !"web_search".equals(delegate)
                        && !"file_tool".equals(delegate);
                String controllerApi = controllerUsedLlm ? llmClient.getProvider() : "mock";
                String controllerResp = switch (delegate) {
                    case "file_tool" -> "Using **file tools** to handle: " + truncate(lastUserMessage, 100);
                    case "jira_import" -> "Importing tickets from **Jira export**: " + truncate(lastUserMessage, 100);
                    case "web_search" -> "Requesting **web search** for: " + truncate(lastUserMessage, 100);
                    default -> "Delegating to **" + delegate + "**: " + truncate(lastUserMessage, 100);
                };
                streamTokens(sessionId, controllerResp);
                long controllerDuration = System.currentTimeMillis() - stepStart;
                eventService.emit(sessionId, EventType.AGENT_STEP_COMPLETED,
                        Map.of("step", 1, "agentId", "controller", "durationMs", controllerDuration,
                                "apiProvider", controllerApi, "mocked", !controllerUsedLlm));
                System.out.printf("  [AgentLoop] step=1 agent=controller api=%s -> delegate=%s (%dms)%n",
                        controllerApi, delegate, controllerDuration);

                Thread.sleep(200);

                // === Step 2 — Specialist ===
                if (Thread.currentThread().isInterrupted()) { updateStatus(sessionId, isThread, SessionStatus.PAUSED); return; }
                stepStart = System.currentTimeMillis();
                lockService.renew(sessionId, owner);
                eventService.emit(sessionId, EventType.AGENT_STEP_STARTED,
                        Map.of("step", 2, "agentId", delegate));
                String specialistResp = generateSpecialistResponse(delegate, lastUserMessage, sessionId);
                long specialistDuration = System.currentTimeMillis() - stepStart;
                boolean usedRealLlm = llmClient != null && llmClient.isAvailable()
                        && !"jira_import".equals(delegate) && !"web_search".equals(delegate)
                        && !"file_tool".equals(delegate);
                String apiProvider = usedRealLlm ? llmClient.getProvider() : "mock";
                streamTokens(sessionId, specialistResp);
                eventService.emit(sessionId, EventType.AGENT_STEP_COMPLETED,
                        Map.of("step", 2, "agentId", delegate, "durationMs", specialistDuration,
                                "apiProvider", apiProvider, "mocked", !usedRealLlm));
                System.out.printf("  [AgentLoop] step=2 agent=%s api=%s response=%d chars (%dms)%n",
                        delegate, apiProvider, specialistResp.length(), specialistDuration);

                // Save specialist response as assistant message with metadata
                long seq = messageRepo.countBySessionId(sessionId) + 1;
                MessageDoc assistantMsg = new MessageDoc();
                assistantMsg.messageId = UUID.randomUUID().toString();
                assistantMsg.sessionId = sessionId;
                assistantMsg.seq = seq;
                assistantMsg.role = "assistant";
                assistantMsg.content = specialistResp;
                assistantMsg.timestamp = Instant.now();
                assistantMsg.agentId = delegate;
                assistantMsg.apiProvider = apiProvider;
                assistantMsg.durationMs = specialistDuration;
                assistantMsg.mocked = !usedRealLlm;
                messageRepo.save(assistantMsg);

                Thread.sleep(200);

                // === Step 3 — Reviewer (uses real LLM when available) ===
                if (Thread.currentThread().isInterrupted()) { updateStatus(sessionId, isThread, SessionStatus.PAUSED); return; }
                stepStart = System.currentTimeMillis();
                lockService.renew(sessionId, owner);
                eventService.emit(sessionId, EventType.AGENT_STEP_STARTED,
                        Map.of("step", 3, "agentId", "reviewer"));

                String reviewerResp;
                boolean reviewerUsedLlm = false;
                if (llmClient != null && llmClient.isAvailable()) {
                    reviewerResp = runLlmReviewer(lastUserMessage, specialistResp, delegate, sessionId);
                    reviewerUsedLlm = true;
                } else {
                    reviewerResp = "**Review: PASS** — Response addresses the user's request.\n\n"
                            + "---\n*Reviewer is using **mock mode** — configure an API key for real quality checks.*";
                }
                streamTokens(sessionId, reviewerResp);
                long reviewerDuration = System.currentTimeMillis() - stepStart;
                String reviewerApi = reviewerUsedLlm ? llmClient.getProvider() : "mock";
                eventService.emit(sessionId, EventType.AGENT_STEP_COMPLETED,
                        Map.of("step", 3, "agentId", "reviewer", "done", true, "durationMs", reviewerDuration,
                                "apiProvider", reviewerApi, "mocked", !reviewerUsedLlm));
                System.out.printf("  [AgentLoop] step=3 agent=reviewer api=%s %s (%dms)%n",
                        reviewerApi, reviewerResp.contains("PASS") ? "PASS" : "REVIEW", reviewerDuration);

                // Checkpoint
                cpService.create(sessionId, 3, Map.of("stepNo", 3, "messageCount", messages.size() + 1));

                updateStatus(sessionId, isThread, SessionStatus.COMPLETED);
                System.out.printf("  [AgentLoop] COMPLETED session=%s total=%dms%n",
                        sessionId.substring(0, 8), System.currentTimeMillis() - loopStart);
                if (distillerService != null) distillerService.distillAsync(sessionId);
            } catch (Exception e) {
                String errMsg = e.getMessage() != null ? e.getMessage() : "Unknown error";
                System.out.printf("  [AgentLoop] FAILED session=%s error=%s total=%dms%n",
                        sessionId.substring(0, 8), errMsg, System.currentTimeMillis() - loopStart);
                updateStatus(sessionId, false, SessionStatus.FAILED);
                eventService.emit(sessionId, EventType.ERROR, Map.of("message", errMsg));
            } finally {
                lockService.release(sessionId, owner);
                running.remove(sessionId);
            }
        }

        /** Run the reviewer using real LLM — returns PASS, FAIL, or OPTIONS with choices */
        private String runLlmReviewer(String userRequest, String agentResponse, String delegate, String sessionId) {
            try {
                String systemPrompt = AGENT_SYSTEM_PROMPTS.getOrDefault("reviewer",
                        "Review the response for completeness.");

                // Build review context: user request + agent response
                var reviewMessages = new java.util.ArrayList<Map<String, String>>();
                reviewMessages.add(Map.of("role", "user", "content",
                        "User's original request: " + userRequest
                        + "\n\nAgent (" + delegate + ") response:\n" + agentResponse
                        + "\n\nDid the agent fully address the user's request? "
                        + "Reply PASS if complete, FAIL with explanation if wrong, "
                        + "or OPTIONS with numbered choices if the response was partial "
                        + "(e.g., listed a directory but didn't read files the user wanted)."));

                String review = llmClient.chat(systemPrompt, reviewMessages);
                if (review != null && !review.isBlank()) {
                    // Prefix with **Review:** for consistent UI display
                    if (review.toUpperCase().contains("PASS")) {
                        return "**Review: PASS** — " + review.replaceAll("(?i)^\\s*PASS[:\\s]*", "").trim();
                    } else if (review.toUpperCase().contains("OPTIONS")) {
                        return "**Review: OPTIONS** — " + review.replaceAll("(?i)^\\s*OPTIONS[:\\s]*", "").trim();
                    } else if (review.toUpperCase().contains("FAIL")) {
                        return "**Review: NEEDS MORE** — " + review.replaceAll("(?i)^\\s*FAIL[:\\s]*", "").trim();
                    }
                    return "**Review:** " + review;
                }
            } catch (Exception e) {
                System.err.println("  [Reviewer] LLM call failed: " + e.getMessage());
            }
            return "**Review: PASS** — (reviewer LLM call failed, defaulting to pass)";
        }

        private void updateStatus(String sessionId, boolean isThread, SessionStatus status) {
            if (isThread) {
                threadRepo.findById(sessionId).ifPresent(t -> {
                    t.status = status; t.updatedAt = Instant.now(); threadRepo.save(t);
                });
            } else {
                sessionRepo.findById(sessionId).ifPresent(s -> {
                    s.status = status; s.updatedAt = Instant.now(); sessionRepo.save(s);
                });
            }
            eventService.emit(sessionId, EventType.SESSION_STATUS_CHANGED, Map.of("status", status.name()));
        }

        private String determineDelegate(String userMessage, String sessionId) {
            if (userMessage == null || userMessage.isBlank()) return "generalist";
            String lower = userMessage.toLowerCase();
            // Tool-level routing — these have dedicated handlers, not LLM agents
            if (isImportRequest(lower)) return "jira_import";
            if (isSearchRequest(lower)) return "web_search";
            if (isFileRequest(lower) || containsFilePath(userMessage)) return "file_tool";

            // === LLM-based routing: ask the controller LLM which agent should handle this ===
            if (llmClient != null && llmClient.isAvailable()) {
                String llmDelegate = llmDetermineDelegate(userMessage, sessionId);
                if (llmDelegate != null) return llmDelegate;
            }

            // === Fallback: keyword-based routing when no LLM is available ===
            return keywordFallbackDelegate(lower);
        }

        /** Ask the LLM to pick the best agent for this message */
        private String llmDetermineDelegate(String userMessage, String sessionId) {
            try {
                var sb = new StringBuilder();
                sb.append("You are a routing controller. Given the user's message, decide which specialist agent should handle it.\n\n");
                sb.append("Available agents:\n");
                for (var entry : AGENT_DESCRIPTIONS.entrySet()) {
                    sb.append("- **").append(entry.getKey()).append("**: ").append(entry.getValue()).append("\n");
                }
                sb.append("\nRespond with ONLY the agent name (one of: ")
                  .append(String.join(", ", AGENT_DESCRIPTIONS.keySet()))
                  .append("). Nothing else — no explanation, no punctuation, just the agent name.");

                var messages = List.of(Map.of("role", "user", "content", userMessage));
                String response = llmClient.chat(sb.toString(), messages);
                if (response != null) {
                    String cleaned = response.strip().toLowerCase().replaceAll("[^a-z_]", "");
                    if (AGENT_DESCRIPTIONS.containsKey(cleaned)) {
                        System.out.printf("  [Controller] LLM routed to: %s%n", cleaned);
                        return cleaned;
                    }
                    System.err.printf("  [Controller] LLM returned unknown agent: '%s', falling back%n", response.strip());
                }
            } catch (Exception e) {
                System.err.println("  [Controller] LLM routing failed: " + e.getMessage());
            }
            return null; // fall back to keyword
        }

        /** Keyword-based fallback when no LLM is available */
        private String keywordFallbackDelegate(String lower) {
            if (lower.contains("code") || lower.contains("bug") || lower.contains("fix")
                    || lower.contains("implement") || lower.contains("debug")
                    || lower.contains("function") || lower.contains("class") || lower.contains("refactor")
                    || lower.contains("compile") || lower.contains("syntax") || lower.contains("error in")) {
                return "coder";
            }
            if (lower.contains("sprint") || lower.contains("plan") || lower.contains("ticket")
                    || lower.contains("milestone") || lower.contains("deadline") || lower.contains("roadmap")
                    || lower.contains("backlog") || lower.contains("standup") || lower.contains("retro")
                    || lower.contains("assign") || lower.contains("prioriti") || lower.contains("estimate")) {
                return "pm";
            }
            if (lower.contains("remind") || lower.contains("schedule") || lower.contains("alarm")
                    || lower.contains("timer") || lower.contains("at ") && lower.contains("pm")
                    || lower.contains("tomorrow") || lower.contains("every day")
                    || lower.contains("recurring") || lower.contains("don't forget")) {
                return "reminder";
            }
            return "generalist";
        }

        /** Check if the message contains file/directory paths */
        private boolean containsFilePath(String msg) {
            return msg.matches(".*(/[\\w.\\-]+){2,}.*")           // Unix absolute path
                    || msg.matches(".*[A-Za-z]:\\\\[\\w.\\\\\\-]+.*"); // Windows path
        }

        /** Detect Jira/Excel/CSV import requests */
        private boolean isImportRequest(String lower) {
            boolean hasImportVerb = lower.contains("import") || lower.contains("load") || lower.contains("ingest")
                    || lower.contains("read") || lower.contains("parse") || lower.contains("create tickets from");
            boolean hasImportContext = lower.contains("jira") || lower.contains("excel") || lower.contains(".xlsx")
                    || lower.contains(".xls") || lower.contains(".csv") || lower.contains("spreadsheet")
                    || lower.contains("ticket") && (lower.contains("file") || lower.contains("dump") || lower.contains("export"));
            return hasImportVerb && hasImportContext;
        }

        /** Detect web search requests (weather, news, real-time info) */
        private boolean isSearchRequest(String lower) {
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

        /** Emit a SEARCH_REQUESTED event and return a placeholder response */
        private String executeWebSearch(String userMessage, String sessionId) {
            // Build a Google search URL from the user's query
            String query = userMessage.replaceAll("(?i)(what is|what's|tell me|show me|search for|look up|find out|google)\\s*", "").trim();
            String encoded = java.net.URLEncoder.encode(query, java.nio.charset.StandardCharsets.UTF_8);
            String searchUrl = "https://www.google.com/search?q=" + encoded;
            String requestId = UUID.randomUUID().toString();

            // Emit the search request event — the UI will open the search pane
            eventService.emit(sessionId, EventType.SEARCH_REQUESTED,
                    Map.of("requestId", requestId, "query", query, "searchUrl", searchUrl));

            System.out.printf("  [WebSearch] query=\"%s\" requestId=%s%n", query, requestId.substring(0, 8));

            return "**Web Search Requested**\n\n"
                    + "I need to search the web for: **" + query + "**\n\n"
                    + "The search pane should open automatically with a Google search. "
                    + "Please paste the relevant results there and click **Submit**.\n\n"
                    + "Once I receive the search results, send another message and I'll incorporate the information.";
        }

        /** Execute Jira import from a file into a project */
        private String executeJiraImport(String userMessage, String sessionId) {
            var paths = extractPaths(userMessage);
            if (paths.isEmpty()) {
                return "I need a file path to import from. Please provide the full path to a CSV or Excel file exported from Jira.\n\n"
                        + "**Example:** `import tickets from /path/to/jira-export.csv`";
            }

            // Determine the project ID — check if this session belongs to a thread with a project
            String projectId = resolveProjectId(sessionId);
            if (projectId == null) {
                return "**Error:** Could not determine the project for this session. Please use a thread that belongs to a project, or specify the project.";
            }

            String filePath = paths.get(0);
            System.out.printf("  [JiraImport] file=%s project=%s%n", filePath, projectId);
            var result = jiraImportService.importFile(filePath, projectId);

            var sb = new StringBuilder();
            sb.append("**Jira Import Results**\n\n");
            sb.append("- **File:** `").append(filePath).append("`\n");
            sb.append("- **Imported:** ").append(result.imported()).append(" / ").append(result.total()).append(" rows\n");
            if (!result.errors().isEmpty()) {
                sb.append("- **Errors:** ").append(result.errors().size()).append("\n\n");
                sb.append("**Error details:**\n");
                for (String err : result.errors().stream().limit(10).toList()) {
                    sb.append("  - ").append(err).append("\n");
                }
                if (result.errors().size() > 10) {
                    sb.append("  - ... and ").append(result.errors().size() - 10).append(" more\n");
                }
            }
            if (result.imported() > 0) {
                sb.append("\nTickets have been created in the project. Use the ticket list to review them.");
            }
            System.out.printf("  [JiraImport] imported=%d/%d errors=%d%n", result.imported(), result.total(), result.errors().size());
            return sb.toString();
        }

        /** Resolve the project ID from a session/thread context */
        private String resolveProjectId(String sessionId) {
            // Check if it's a thread with project IDs
            var thread = threadRepo.findById(sessionId).orElse(null);
            if (thread != null) {
                var pids = thread.getEffectiveProjectIds();
                return pids.isEmpty() ? null : pids.get(0);
            }
            // Check if it's a session linked to a thread
            var session = sessionRepo.findById(sessionId).orElse(null);
            if (session != null && session.threadId != null) {
                var t = threadRepo.findById(session.threadId).orElse(null);
                if (t != null) {
                    var pids = t.getEffectiveProjectIds();
                    return pids.isEmpty() ? null : pids.get(0);
                }
            }
            return null;
        }

        /**
         * Handle context commands: use project, use thread, whereami.
         * Returns a response string if a command was handled, or null if not a command.
         */
        private String handleContextCommand(String msg, String sessionId, boolean isThread) {
            String lower = msg.toLowerCase();

            // --- whereami ---
            if (lower.equals("whereami") || lower.equals("where am i") || lower.equals("where am i?")) {
                return buildWhereAmI(sessionId, isThread);
            }

            // --- use project [name] ---
            if (lower.startsWith("use project ")) {
                String projectName = msg.substring("use project ".length()).trim();
                return handleUseProject(projectName, sessionId, isThread);
            }

            // --- use thread [name] ---
            if (lower.startsWith("use thread ")) {
                String threadName = msg.substring("use thread ".length()).trim();
                return handleUseThread(threadName, sessionId, isThread);
            }

            return null; // Not a command
        }

        private String buildWhereAmI(String sessionId, boolean isThread) {
            var sb = new StringBuilder("**Current Context**\n\n");

            if (isThread) {
                var thread = threadRepo.findById(sessionId).orElse(null);
                if (thread != null) {
                    sb.append("- **Thread:** ").append(thread.title).append(" (`").append(thread.threadId, 0, 8).append("...`)\n");
                    var pids = thread.getEffectiveProjectIds();
                    if (!pids.isEmpty()) {
                        for (String pid : pids) {
                            var proj = projectRepo.findById(pid).orElse(null);
                            sb.append("- **Project:** ").append(proj != null ? proj.name : "unknown")
                              .append(" (`").append(pid, 0, Math.min(8, pid.length())).append("...`)\n");
                        }
                    } else {
                        sb.append("- **Project:** none attached\n");
                    }
                } else {
                    sb.append("- **Thread:** not found\n");
                }
            } else {
                var session = sessionRepo.findById(sessionId).orElse(null);
                if (session != null) {
                    sb.append("- **Session:** `").append(sessionId, 0, 8).append("...`\n");
                    if (session.threadId != null) {
                        var thread = threadRepo.findById(session.threadId).orElse(null);
                        sb.append("- **Thread:** ").append(thread != null ? thread.title : session.threadId).append("\n");
                        if (thread != null) {
                            var pids = thread.getEffectiveProjectIds();
                            for (String pid : pids) {
                                var proj = projectRepo.findById(pid).orElse(null);
                                sb.append("- **Project:** ").append(proj != null ? proj.name : pid).append("\n");
                            }
                        }
                    } else {
                        sb.append("- **Thread:** none (standalone session)\n");
                        sb.append("- **Project:** none\n");
                        sb.append("\nUse `use project <name>` to attach this session to a project.");
                    }
                }
            }

            // Show message count
            long msgCount = messageRepo.countBySessionId(sessionId);
            sb.append("- **Messages:** ").append(msgCount).append("\n");
            return sb.toString();
        }

        private String handleUseProject(String projectName, String sessionId, boolean isThread) {
            // Find project by name (case-insensitive)
            var project = projectRepo.findByNameIgnoreCase(projectName).orElse(null);
            if (project == null) {
                // List available projects
                var all = projectRepo.findAllByOrderByUpdatedAtDesc();
                var sb = new StringBuilder("**Project not found:** `" + projectName + "`\n\n");
                if (all.isEmpty()) {
                    sb.append("No projects exist yet. Create one first.");
                } else {
                    sb.append("**Available projects:**\n");
                    for (var p : all) sb.append("- ").append(p.name).append("\n");
                }
                return sb.toString();
            }

            if (isThread) {
                // Thread: add project to projectIds
                var thread = threadRepo.findById(sessionId).orElse(null);
                if (thread != null) {
                    var pids = thread.projectIds != null ? new java.util.ArrayList<>(thread.projectIds) : new java.util.ArrayList<String>();
                    if (!pids.contains(project.projectId)) {
                        pids.add(project.projectId);
                        thread.projectIds = pids;
                        thread.updatedAt = Instant.now();
                        threadRepo.save(thread);
                    }
                    return "**Attached to project:** " + project.name + "\n\n"
                            + "Thread `" + thread.title + "` is now linked to project **" + project.name + "**.\n"
                            + "You can now import tickets, manage resources, and work within this project context.";
                }
            } else {
                // Standalone session: create or find a thread in this project and link
                var session = sessionRepo.findById(sessionId).orElse(null);
                if (session != null) {
                    if (session.threadId == null) {
                        // Create a new thread in the project for this session
                        ThreadDoc newThread = new ThreadDoc();
                        newThread.threadId = UUID.randomUUID().toString();
                        newThread.projectIds = List.of(project.projectId);
                        newThread.title = "Session " + sessionId.substring(0, 8);
                        newThread.status = SessionStatus.IDLE;
                        newThread.createdAt = Instant.now();
                        newThread.updatedAt = Instant.now();
                        threadRepo.save(newThread);
                        session.threadId = newThread.threadId;
                    } else {
                        // Update existing thread
                        var thread = threadRepo.findById(session.threadId).orElse(null);
                        if (thread != null) {
                            var pids = thread.projectIds != null ? new java.util.ArrayList<>(thread.projectIds) : new java.util.ArrayList<String>();
                            if (!pids.contains(project.projectId)) {
                                pids.add(project.projectId);
                                thread.projectIds = pids;
                                thread.updatedAt = Instant.now();
                                threadRepo.save(thread);
                            }
                        }
                    }
                    session.updatedAt = Instant.now();
                    sessionRepo.save(session);
                    return "**Attached to project:** " + project.name + "\n\n"
                            + "Session is now linked to project **" + project.name + "**.\n"
                            + "You can now use project features like tickets, resources, and imports.";
                }
            }
            return "**Error:** Could not attach to project.";
        }

        private String handleUseThread(String threadName, String sessionId, boolean isThread) {
            // First resolve the project context
            String projectId = resolveProjectId(sessionId);

            if (projectId == null) {
                return "**No project selected.** Use `use project <name>` first, then `use thread <name>`.";
            }

            // Find thread by title within the project
            var thread = threadRepo.findByTitleIgnoreCaseAndProjectIdsContaining(threadName, projectId).orElse(null);

            if (thread == null) {
                // Create a new thread
                ThreadDoc newThread = new ThreadDoc();
                newThread.threadId = UUID.randomUUID().toString();
                newThread.projectIds = List.of(projectId);
                newThread.title = threadName;
                newThread.status = SessionStatus.IDLE;
                newThread.createdAt = Instant.now();
                newThread.updatedAt = Instant.now();
                threadRepo.save(newThread);

                var project = projectRepo.findById(projectId).orElse(null);
                String projectName = project != null ? project.name : projectId;

                // If this is a session, link it to the new thread
                if (!isThread) {
                    var session = sessionRepo.findById(sessionId).orElse(null);
                    if (session != null) {
                        session.threadId = newThread.threadId;
                        session.updatedAt = Instant.now();
                        sessionRepo.save(session);
                    }
                }

                return "**Created and switched to thread:** " + threadName + "\n\n"
                        + "New thread `" + threadName + "` created in project **" + projectName + "**.";
            }

            // Thread exists — link session to it
            if (!isThread) {
                var session = sessionRepo.findById(sessionId).orElse(null);
                if (session != null) {
                    session.threadId = thread.threadId;
                    session.updatedAt = Instant.now();
                    sessionRepo.save(session);
                }
            }

            var project = projectRepo.findById(projectId).orElse(null);
            String projectName = project != null ? project.name : projectId;
            long threadMsgCount = messageRepo.countBySessionId(thread.threadId);

            return "**Switched to thread:** " + thread.title + "\n\n"
                    + "- **Project:** " + projectName + "\n"
                    + "- **Thread:** " + thread.title + " (`" + thread.threadId.substring(0, 8) + "...`)\n"
                    + "- **Messages in thread:** " + threadMsgCount + "\n";
        }

        /** Detect requests to read/inspect/list files */
        private boolean isFileRequest(String lower) {
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

        /** Extract file paths from user message */
        private List<String> extractPaths(String message) {
            var paths = new java.util.ArrayList<String>();
            // Match absolute Unix paths
            var unixMatcher = java.util.regex.Pattern.compile("(/[\\w.\\-]+){2,}").matcher(message);
            while (unixMatcher.find()) paths.add(unixMatcher.group());
            // Match Windows paths
            var winMatcher = java.util.regex.Pattern.compile("[A-Za-z]:\\\\[\\w.\\\\\\-]+").matcher(message);
            while (winMatcher.find()) paths.add(winMatcher.group());
            return paths;
        }

        /** Execute a file tool and return the result */
        private String executeFileTool(String userMessage, String sessionId) {
            var paths = extractPaths(userMessage);
            String lower = userMessage.toLowerCase();

            // Context-aware: if no paths in current message, check recent session messages
            if (paths.isEmpty()) {
                var recentMsgs = messageRepo.findBySessionIdOrderBySeqAsc(sessionId);
                for (int i = recentMsgs.size() - 1; i >= 0 && paths.isEmpty(); i--) {
                    paths = extractPaths(recentMsgs.get(i).content);
                }
            }
            var sb = new StringBuilder();

            if (lower.contains("list dir") || lower.contains("list folder") || lower.contains("ls ")) {
                // List directory operation
                String dirPath = paths.isEmpty() ? "." : paths.get(0);
                try {
                    var dir = java.nio.file.Path.of(dirPath);
                    if (!java.nio.file.Files.isDirectory(dir)) {
                        return "**Error:** `" + dirPath + "` is not a directory.";
                    }
                    sb.append("**Directory listing of** `").append(dirPath).append("`:\n\n");
                    sb.append("| Name | Type | Size |\n|------|------|------|\n");
                    try (var stream = java.nio.file.Files.list(dir)) {
                        stream.sorted().forEach(p -> {
                            boolean isDir = java.nio.file.Files.isDirectory(p);
                            String size = "-";
                            if (!isDir) {
                                try { size = java.nio.file.Files.size(p) + " B"; } catch (Exception ignored) {}
                            }
                            sb.append("| ").append(p.getFileName()).append(" | ")
                              .append(isDir ? "DIR" : "FILE").append(" | ").append(size).append(" |\n");
                        });
                    }
                    System.out.printf("  [FileTool] list_directory path=%s%n", dirPath);
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
                    var filePath = java.nio.file.Path.of(path);
                    if (!java.nio.file.Files.exists(filePath)) {
                        sb.append("**File not found:** `").append(path).append("`\n\n");
                        continue;
                    }
                    if (java.nio.file.Files.isDirectory(filePath)) {
                        // Auto-list AND auto-read small text files in the directory
                        sb.append("**Directory listing of** `").append(path).append("`:\n\n");
                        sb.append("| Name | Type | Size |\n|------|------|------|\n");
                        var readableFiles = new java.util.ArrayList<java.nio.file.Path>();
                        try (var dirStream = java.nio.file.Files.list(filePath)) {
                            dirStream.sorted().forEach(p2 -> {
                                boolean d = java.nio.file.Files.isDirectory(p2);
                                String sz = "-";
                                if (!d) {
                                    try { sz = java.nio.file.Files.size(p2) + " B"; } catch (Exception ignored) {}
                                    // Collect readable text files (< 50KB, text-like extensions)
                                    String fname = p2.getFileName().toString().toLowerCase();
                                    if (fname.matches(".*\\.(txt|md|json|csv|xml|yaml|yml|properties|cfg|conf|ini|log|java|py|js|ts|html|css|sh|bat|sql)$")) {
                                        try { if (java.nio.file.Files.size(p2) < 50_000) readableFiles.add(p2); }
                                        catch (Exception ignored) {}
                                    }
                                }
                                sb.append("| ").append(p2.getFileName()).append(" | ")
                                  .append(d ? "DIR" : "FILE").append(" | ").append(sz).append(" |\n");
                            });
                        } catch (Exception de) {
                            sb.append("Error listing: ").append(de.getMessage()).append("\n");
                        }

                        // If user asked to "read" files (not just list), auto-read them
                        boolean wantsRead = lower.contains("read") || lower.contains("show") || lower.contains("contents")
                                || lower.contains("open") || lower.contains("what");
                        if (wantsRead && !readableFiles.isEmpty()) {
                            sb.append("\n---\n**Reading ").append(readableFiles.size()).append(" text file(s):**\n\n");
                            for (var rf : readableFiles) {
                                try {
                                    String fc = java.nio.file.Files.readString(rf);
                                    if (fc.length() > 5_000) fc = fc.substring(0, 5_000) + "\n... [truncated]";
                                    String ext2 = rf.getFileName().toString();
                                    ext2 = ext2.contains(".") ? ext2.substring(ext2.lastIndexOf('.') + 1) : "";
                                    sb.append("**").append(rf.getFileName()).append(":**\n");
                                    sb.append("```").append(ext2).append("\n").append(fc).append("\n```\n\n");
                                    System.out.printf("  [FileTool] auto-read %s%n", rf);
                                } catch (Exception re) {
                                    sb.append("Error reading ").append(rf.getFileName()).append(": ").append(re.getMessage()).append("\n\n");
                                }
                            }
                        } else if (!wantsRead && !readableFiles.isEmpty()) {
                            sb.append("\n**").append(readableFiles.size()).append(" readable file(s) found.** ")
                              .append("Options:\n1. **Read files** — say \"read the files\"\n")
                              .append("2. **Do nothing** — continue chatting\n")
                              .append("3. **Let's chat** — ask me about something else\n");
                        }
                        sb.append("\n");
                        System.out.printf("  [FileTool] auto-list directory path=%s files=%d readable=%d%n",
                                path, readableFiles.size(), readableFiles.size());
                        continue;
                    }
                    long size = java.nio.file.Files.size(filePath);
                    String content = java.nio.file.Files.readString(filePath);
                    // Truncate very large files
                    if (content.length() > 10_000) {
                        content = content.substring(0, 10_000) + "\n... [truncated, " + size + " bytes total]";
                    }
                    String ext = path.contains(".") ? path.substring(path.lastIndexOf('.') + 1) : "";
                    sb.append("**File:** `").append(path).append("` (").append(size).append(" bytes)\n\n");
                    sb.append("```").append(ext).append("\n").append(content).append("\n```\n\n");
                    System.out.printf("  [FileTool] read_file path=%s size=%d%n", path, size);
                } catch (Exception e) {
                    sb.append("**Error reading** `").append(path).append("`: ").append(e.getMessage()).append("\n\n");
                }
            }

            // If the user's message has an intent beyond just reading (e.g., "tell me about reminders",
            // "what should I do", "summarize"), pass the file contents to the LLM for analysis
            String fileContent = sb.toString();
            boolean hasAnalysisIntent = lower.contains("tell me") || lower.contains("about")
                    || lower.contains("remind") || lower.contains("summarize") || lower.contains("analyze")
                    || lower.contains("suggest") || lower.contains("what should") || lower.contains("help me")
                    || lower.contains("extract") || lower.contains("find");
            if (hasAnalysisIntent && llmClient != null && llmClient.isAvailable() && !fileContent.isBlank()) {
                // Save file content as context, then let the LLM analyze it
                long seq = messageRepo.countBySessionId(sessionId) + 1;
                MessageDoc fileCtxMsg = new MessageDoc();
                fileCtxMsg.messageId = UUID.randomUUID().toString();
                fileCtxMsg.sessionId = sessionId;
                fileCtxMsg.seq = seq;
                fileCtxMsg.role = "assistant";
                fileCtxMsg.content = "[File Tool Results]\n" + fileContent;
                fileCtxMsg.timestamp = Instant.now();
                fileCtxMsg.agentId = "file_tool";
                fileCtxMsg.mocked = false;
                messageRepo.save(fileCtxMsg);

                // Now call the LLM with the generalist prompt + full context
                String llmAnalysis = callRealLlm("generalist", userMessage, sessionId);
                return fileContent + "\n---\n**Analysis:**\n\n" + llmAnalysis;
            }

            return fileContent;
        }

        /** Execute a reminder request — uses LLM with FULL session context to identify reminders */
        private String executeReminder(String userMessage, String sessionId) {
            // Use callRealLlm which loads the full conversation history — this lets the reminder
            // agent see previous file listings, directory contents, etc. and extract reminders from context
            if (llmClient != null && llmClient.isAvailable()) {
                String response = callRealLlm("reminder", userMessage, sessionId);

                // Parse REMINDER lines from the response and save to DB
                int savedCount = 0;
                for (String line : response.split("\n")) {
                    line = line.trim();
                    if (line.startsWith("REMINDER:") || line.contains("| WHEN:")) {
                        var doc = new ReminderDoc();
                        doc.reminderId = UUID.randomUUID().toString();
                        doc.sessionId = sessionId;
                        doc.createdAt = Instant.now();

                        // Parse "REMINDER: what | WHEN: time | RECURRING: yes/no"
                        String[] parts = line.split("\\|");
                        doc.message = parts[0].replaceAll("(?i)^\\s*REMINDER:\\s*", "").trim();
                        doc.triggerAt = "unspecified";
                        doc.recurring = false;
                        for (String part : parts) {
                            part = part.trim();
                            if (part.toUpperCase().startsWith("WHEN:"))
                                doc.triggerAt = part.substring(5).trim();
                            else if (part.toUpperCase().startsWith("RECURRING:")) {
                                String val = part.substring(10).trim().toLowerCase();
                                doc.recurring = val.startsWith("yes");
                                if (val.contains("daily")) doc.intervalMs = 86_400_000L;
                                else if (val.contains("weekly")) doc.intervalMs = 604_800_000L;
                                else if (val.contains("hourly")) doc.intervalMs = 3_600_000L;
                            }
                        }
                        if (!doc.message.isBlank()) {
                            reminderRepo.save(doc);
                            savedCount++;
                            System.out.printf("  [Reminder] created id=%s msg=%s when=%s%n",
                                    doc.reminderId.substring(0, 8), truncate(doc.message, 50), doc.triggerAt);
                        }
                    }
                }
                if (savedCount > 0) {
                    System.out.printf("  [Reminder] saved %d reminders from LLM response%n", savedCount);
                }
                return response;
            }

            // Fallback: basic reminder without LLM
            var doc = new ReminderDoc();
            doc.reminderId = UUID.randomUUID().toString();
            doc.sessionId = sessionId;
            doc.message = userMessage;
            doc.triggerAt = "unspecified";
            doc.recurring = false;
            doc.createdAt = Instant.now();
            reminderRepo.save(doc);
            System.out.printf("  [Reminder] created (mock) id=%s%n", doc.reminderId.substring(0, 8));

            String mockWarning = "\n\n---\n*This is a **basic reminder** — no LLM API key is configured. "
                    + "Press **Ctrl+K** to add your API key for smarter scheduling.*";
            return "Reminder saved: **" + truncate(userMessage, 200) + "**\n\n"
                    + "I've stored this reminder but can't parse specific timing without an LLM. "
                    + "Once an API key is configured, I'll be able to understand natural language scheduling." + mockWarning;
        }

        /** Rich system prompts per agent role — each includes skills the agent can use */
        private static final Map<String, String> AGENT_SYSTEM_PROMPTS = new java.util.HashMap<>(Map.of(
                "coder", """
                    You are a senior software engineer. Your skills include:
                    - Writing code in Java, Python, JavaScript, and other languages
                    - Debugging, fixing bugs, and analyzing stack traces
                    - Code review and refactoring suggestions
                    - Explaining technical concepts clearly
                    - Reading and analyzing files when provided as context
                    You have access to a FILE TOOL that can read files and list directories on the user's system.
                    If the user references a file path, the system will read it for you automatically.
                    Be concise and provide working code examples. Use markdown formatting.""",
                "pm", """
                    You are a project manager and assistant. Your skills include:
                    - Sprint planning, backlog grooming, and task estimation
                    - Creating and organizing tickets and milestones
                    - Resource allocation and deadline tracking
                    - Roadmap planning and stakeholder communication
                    - Retrospective facilitation and team workflow optimization
                    You have access to the session and thread history for context.
                    Be practical, clear, and focused on outcomes. Use markdown formatting.""",
                "generalist", """
                    You are a helpful, knowledgeable AI assistant. Your skills include:
                    - Answering general knowledge questions on any topic
                    - Giving life advice, wellness tips, and personal productivity suggestions
                    - Brainstorming ideas and creative problem-solving
                    - Summarizing information and explaining complex topics simply
                    - Helping with writing, communication, and decision-making
                    You have access to the full conversation history for context.
                    Be friendly, concise, and helpful. Use markdown formatting.""",
                "reminder", """
                    You are a scheduling and reminder assistant. Your skills include:
                    - Creating reminders for tasks, events, and deadlines
                    - Reading files or previous conversation context to identify things the user should be reminded about
                    - Suggesting optimal times based on the user's described schedule
                    - Organizing recurring reminders (daily, weekly, etc.)
                    - Prioritizing tasks by urgency and importance
                    You have access to the full conversation history. If files were read or listed earlier in the conversation,
                    use that content to identify reminders. When you identify reminders, list each one clearly with:
                    REMINDER: <what> | WHEN: <time> | RECURRING: <yes/no interval>
                    Then provide a friendly summary after. Use markdown formatting.""",
                "controller", """
                    You are a routing controller that delegates tasks to specialist agents.
                    You decide which agent should handle each user request.""",
                "reviewer", """
                    You are a quality reviewer. Review the specialist agent's response and decide:
                    - PASS: The response fully addresses the user's request
                    - FAIL: The response is incomplete, wrong, or misses the point
                    - OPTIONS: The response was partial (e.g., listed files but didn't read them). Offer the user numbered options.
                    When reviewing, consider the FULL user request, not just the literal response.
                    For example, if the user asked to read files and the agent only listed a directory, that's incomplete — suggest reading the files.
                    Respond with one of: PASS, FAIL, or OPTIONS followed by numbered choices.
                    Use markdown formatting."""
        ));

        /** Agent descriptions used by the LLM controller to decide routing */
        private static final Map<String, String> AGENT_DESCRIPTIONS = Map.of(
                "coder", "Handles coding tasks: writing code, debugging, code review, technical explanations, file analysis",
                "pm", "Handles project management: sprint planning, tickets, milestones, backlog, resource allocation, deadlines",
                "generalist", "Handles general questions: life advice, brainstorming, knowledge questions, writing help, anything that doesn't fit other agents",
                "reminder", "Handles reminders and scheduling: setting reminders, recurring tasks, schedule optimization"
        );

        private String generateSpecialistResponse(String delegate, String userMessage, String threadId) {
            if (userMessage == null) userMessage = "";
            return switch (delegate) {
                case "jira_import" -> executeJiraImport(userMessage, threadId);
                case "web_search" -> executeWebSearch(userMessage, threadId);
                case "file_tool" -> executeFileTool(userMessage, threadId);
                case "reminder" -> executeReminder(userMessage, threadId);
                default -> {
                    // Try real LLM for ANY agent (pm, coder, generalist, etc.)
                    if (llmClient != null && llmClient.isAvailable()) {
                        yield callRealLlm(delegate, userMessage, threadId);
                    }
                    // Fall back to mock with clear warning
                    yield generateMockResponse(delegate, userMessage, threadId);
                }
            };
        }

        /** Call the real LLM (OpenAI or Anthropic) */
        private String callRealLlm(String delegate, String userMessage, String threadId) {
            String systemPrompt = AGENT_SYSTEM_PROMPTS.getOrDefault(delegate,
                    AGENT_SYSTEM_PROMPTS.get("controller"));

            // Build context: include project info if available
            String projectId = resolveProjectId(threadId);
            if (projectId != null) {
                var project = projectRepo.findById(projectId).orElse(null);
                if (project != null) {
                    systemPrompt += "\n\nCurrent project: " + project.name
                            + (project.description != null ? " — " + project.description : "");
                }
            }

            // Build conversation history from messages
            var messages = messageRepo.findBySessionIdOrderBySeqAsc(threadId);
            var llmMessages = new java.util.ArrayList<Map<String, String>>();
            for (var msg : messages) {
                llmMessages.add(Map.of("role", msg.role, "content", msg.content));
            }

            // If coder and files referenced, add file context
            if ("coder".equals(delegate)) {
                var referencedPaths = extractPaths(userMessage);
                for (String path : referencedPaths) {
                    try {
                        var p = java.nio.file.Path.of(path);
                        if (java.nio.file.Files.isRegularFile(p)) {
                            String content = java.nio.file.Files.readString(p);
                            if (content.length() > 5_000) content = content.substring(0, 5_000) + "\n... [truncated]";
                            llmMessages.add(Map.of("role", "user",
                                    "content", "[File context: " + path + "]\n" + content));
                            System.out.printf("  [FileTool] read_file for coder context path=%s%n", path);
                        }
                    } catch (Exception ignored) {}
                }
            }

            System.out.printf("  [LLM] calling %s for agent=%s messages=%d%n",
                    llmClient.getProvider(), delegate, llmMessages.size());
            String response = llmClient.chat(systemPrompt, llmMessages);
            if (response != null && !response.isBlank()) {
                return response;
            }
            // LLM call failed — fall back to mock
            System.err.println("  [LLM] Call failed, falling back to mock");
            return generateMockResponse(delegate, userMessage, threadId);
        }

        /** Generate a canned mock response with a clear warning */
        private String generateMockResponse(String delegate, String userMessage, String threadId) {
            String mockWarning = "\n\n---\n*This is a **canned mock response** — no LLM API key is configured. "
                    + "Press **Ctrl+K** to add your OpenAI or Anthropic API key for real AI responses.*";

            String lower = userMessage.toLowerCase();
            String base = switch (delegate) {
                case "coder" -> {
                    var referencedPaths = extractPaths(userMessage);
                    var fileContext = new StringBuilder();
                    for (String path : referencedPaths) {
                        try {
                            var p = java.nio.file.Path.of(path);
                            if (java.nio.file.Files.isRegularFile(p)) {
                                String content = java.nio.file.Files.readString(p);
                                if (content.length() > 5_000) content = content.substring(0, 5_000) + "\n... [truncated]";
                                fileContext.append("\n**File context** `").append(path).append("`:\n```\n")
                                        .append(content).append("\n```\n");
                            }
                        } catch (Exception ignored) {}
                    }
                    yield "I've analyzed the coding request: \"%s\"\n\n(Mock coder response — configure an API key for real analysis)%s"
                            .formatted(truncate(userMessage, 100),
                                    fileContext.length() > 0 ? "\n\n---\n" + fileContext : "");
                }
                case "pm" -> "I've received your request: \"%s\"\n\n(Mock PM response — configure an API key for real project management advice)"
                        .formatted(truncate(userMessage, 100));
                case "generalist" -> "I've received your question: \"%s\"\n\n(Mock generalist response — configure an API key for real answers)"
                        .formatted(truncate(userMessage, 100));
                default -> "I've processed your request: \"%s\"\n\n(Mock %s response)"
                        .formatted(truncate(userMessage, 100), delegate);
            };
            return base + mockWarning;
        }

        private void streamTokens(String sessionId, String response) throws InterruptedException {
            String[] words = response.split("(?<=\\s)");
            for (String word : words) {
                eventService.emit(sessionId, EventType.MODEL_TOKEN_DELTA, Map.of("token", word));
                Thread.sleep(15);
            }
        }

        private String truncate(String s, int maxLen) {
            if (s == null) return "";
            return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
        }
    }

    // =======================================================================
    // DISTILLER SERVICE
    // =======================================================================

    @Component
    static class DistillerService {
        private final SessionRepo sessionRepo;
        private final ThreadRepo threadRepo;
        private final MessageRepo messageRepo;
        private final MemoryRepo memoryRepo;
        private final EventService eventService;
        private final ExecutorService exec = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "distiller"); t.setDaemon(true); return t;
        });

        DistillerService(SessionRepo sessionRepo, ThreadRepo threadRepo,
                         MessageRepo messageRepo, MemoryRepo memoryRepo,
                         EventService eventService) {
            this.sessionRepo = sessionRepo; this.threadRepo = threadRepo;
            this.messageRepo = messageRepo; this.memoryRepo = memoryRepo;
            this.eventService = eventService;
        }

        void distillAsync(String sessionId) {
            exec.submit(() -> {
                try { distill(sessionId); }
                catch (Exception e) { System.err.println("[distiller] Error distilling " + sessionId + ": " + e.getMessage()); }
            });
        }

        private void distill(String sessionId) {
            // 1. Determine if this is a session or thread
            SessionDoc session = sessionRepo.findById(sessionId).orElse(null);
            boolean isThread = (session == null);
            String threadId = null;

            if (session != null) {
                threadId = session.threadId;
            } else {
                ThreadDoc thread = threadRepo.findById(sessionId).orElse(null);
                if (thread == null) return;
                threadId = sessionId;
            }

            // 2. Load messages
            var messages = messageRepo.findBySessionIdOrderBySeqAsc(sessionId);
            if (messages.size() < 2) return; // nothing worth distilling

            // 3. Extract key content
            String firstUserMsg = messages.stream()
                    .filter(m -> "user".equals(m.role)).map(m -> m.content).findFirst().orElse("");
            String lastAssistantMsg = messages.stream()
                    .filter(m -> "assistant".equals(m.role)).reduce((a, b) -> b).map(m -> m.content).orElse("");

            // 4. Build summary
            String summary = "**Topic:** " + truncate(firstUserMsg, 200) + "\n\n" +
                    "**Outcome:** " + truncate(lastAssistantMsg, 300) + "\n\n" +
                    "**Messages:** " + messages.size() + " (" +
                    messages.stream().filter(m -> "user".equals(m.role)).count() + " user, " +
                    messages.stream().filter(m -> "assistant".equals(m.role)).count() + " assistant)";

            // 5. Store as memory
            MemoryDoc mem = new MemoryDoc();
            mem.memoryId = UUID.randomUUID().toString();
            mem.key = "session-summary-" + sessionId.substring(0, Math.min(8, sessionId.length()));
            mem.content = summary;
            mem.tags = List.of("auto-distilled", "session-summary");
            mem.createdBy = "distiller";
            mem.createdAt = Instant.now();
            mem.updatedAt = Instant.now();

            if (threadId != null) {
                mem.scope = MemoryScope.THREAD;
                mem.threadId = threadId;
                ThreadDoc thread = threadRepo.findById(threadId).orElse(null);
                if (thread != null) {
                    var pids = thread.getEffectiveProjectIds();
                    if (!pids.isEmpty()) mem.projectId = pids.get(0);
                }
            } else {
                mem.scope = MemoryScope.SESSION;
                mem.sessionId = sessionId;
            }

            memoryRepo.save(mem);
            System.out.println("[distiller] Saved memory " + mem.key + " (scope=" + mem.scope + ")");

            // 6. Emit event
            eventService.emit(sessionId, EventType.MEMORY_DISTILLED,
                    Map.of("memoryId", mem.memoryId, "key", mem.key, "scope", mem.scope.name()));
        }

        private String truncate(String s, int maxLen) {
            if (s == null) return "";
            return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
        }
    }

    // =======================================================================
    // LLM CLIENT — Direct HTTP calls to OpenAI / Anthropic APIs
    // =======================================================================

    @Component
    static class LlmClient {
        private final ObjectMapper mapper;
        private final java.net.http.HttpClient httpClient = java.net.http.HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(30))
                .build();

        LlmClient(ObjectMapper mapper) { this.mapper = mapper; }

        /** Check if a real LLM key is available */
        boolean isAvailable() {
            return getOpenAiKey() != null || getAnthropicKey() != null;
        }

        String getProvider() {
            if (getOpenAiKey() != null) return "openai";
            if (getAnthropicKey() != null) return "anthropic";
            return "mock";
        }

        private String getOpenAiKey() {
            String key = ConfigController.openaiKey;
            if (key == null || key.isBlank()) key = System.getenv("OPENAI_API_KEY");
            return (key != null && !key.isBlank()) ? key : null;
        }

        private String getAnthropicKey() {
            String key = ConfigController.anthropicKey;
            if (key == null || key.isBlank()) key = System.getenv("ANTHROPIC_API_KEY");
            return (key != null && !key.isBlank()) ? key : null;
        }

        /** Call the LLM with a system prompt and user message, return the response text */
        String chat(String systemPrompt, List<Map<String, String>> messages) {
            String openaiKey = getOpenAiKey();
            if (openaiKey != null) return callOpenAi(openaiKey, systemPrompt, messages);
            String anthropicKey = getAnthropicKey();
            if (anthropicKey != null) return callAnthropic(anthropicKey, systemPrompt, messages);
            return null; // No key available
        }

        private String callOpenAi(String apiKey, String systemPrompt, List<Map<String, String>> messages) {
            try {
                var body = mapper.createObjectNode();
                body.put("model", "gpt-4o");
                body.put("max_tokens", 2048);

                var msgsArray = body.putArray("messages");
                if (systemPrompt != null) {
                    var sysMsg = msgsArray.addObject();
                    sysMsg.put("role", "system");
                    sysMsg.put("content", systemPrompt);
                }
                for (var msg : messages) {
                    var m = msgsArray.addObject();
                    m.put("role", msg.getOrDefault("role", "user"));
                    m.put("content", msg.getOrDefault("content", ""));
                }

                var request = java.net.http.HttpRequest.newBuilder()
                        .uri(URI.create("https://api.openai.com/v1/chat/completions"))
                        .header("Content-Type", "application/json")
                        .header("Authorization", "Bearer " + apiKey)
                        .timeout(java.time.Duration.ofSeconds(60))
                        .POST(java.net.http.HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                        .build();

                var response = httpClient.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() != 200) {
                    System.err.printf("  [LLM] OpenAI error %d: %s%n", response.statusCode(),
                            response.body().substring(0, Math.min(200, response.body().length())));
                    return null;
                }

                JsonNode json = mapper.readTree(response.body());
                return json.path("choices").path(0).path("message").path("content").asText(null);
            } catch (Exception e) {
                System.err.println("  [LLM] OpenAI call failed: " + e.getMessage());
                return null;
            }
        }

        private String callAnthropic(String apiKey, String systemPrompt, List<Map<String, String>> messages) {
            try {
                var body = mapper.createObjectNode();
                body.put("model", "claude-sonnet-4-20250514");
                body.put("max_tokens", 2048);
                if (systemPrompt != null) body.put("system", systemPrompt);

                var msgsArray = body.putArray("messages");
                for (var msg : messages) {
                    var m = msgsArray.addObject();
                    m.put("role", msg.getOrDefault("role", "user"));
                    m.put("content", msg.getOrDefault("content", ""));
                }

                var request = java.net.http.HttpRequest.newBuilder()
                        .uri(URI.create("https://api.anthropic.com/v1/messages"))
                        .header("Content-Type", "application/json")
                        .header("x-api-key", apiKey)
                        .header("anthropic-version", "2023-06-01")
                        .timeout(java.time.Duration.ofSeconds(60))
                        .POST(java.net.http.HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                        .build();

                var response = httpClient.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() != 200) {
                    System.err.printf("  [LLM] Anthropic error %d: %s%n", response.statusCode(),
                            response.body().substring(0, Math.min(200, response.body().length())));
                    return null;
                }

                JsonNode json = mapper.readTree(response.body());
                return json.path("content").path(0).path("text").asText(null);
            } catch (Exception e) {
                System.err.println("  [LLM] Anthropic call failed: " + e.getMessage());
                return null;
            }
        }
    }

    // =======================================================================
    // JIRA IMPORT SERVICE
    // =======================================================================

    @Component
    static class JiraImportService {
        private final TicketRepo ticketRepo;

        JiraImportService(TicketRepo ticketRepo) { this.ticketRepo = ticketRepo; }

        /** Import tickets from a CSV or Excel file into a project */
        ImportResult importFile(String filePath, String projectId) {
            var path = java.nio.file.Path.of(filePath);
            if (!java.nio.file.Files.exists(path)) {
                return new ImportResult(0, 0, List.of("File not found: " + filePath));
            }
            String name = path.getFileName().toString().toLowerCase();
            try {
                if (name.endsWith(".csv")) return importCsv(path, projectId);
                if (name.endsWith(".xlsx") || name.endsWith(".xls")) return importExcel(path, projectId);
                return new ImportResult(0, 0, List.of("Unsupported file type: " + name + ". Use .csv, .xlsx, or .xls"));
            } catch (Exception e) {
                return new ImportResult(0, 0, List.of("Import error: " + e.getMessage()));
            }
        }

        private ImportResult importCsv(java.nio.file.Path path, String projectId) throws Exception {
            var lines = java.nio.file.Files.readAllLines(path);
            if (lines.isEmpty()) return new ImportResult(0, 0, List.of("CSV file is empty"));

            // Parse header to find column indices
            String[] headers = parseCsvLine(lines.get(0));
            var colMap = mapColumns(headers);
            if (colMap.get("title") < 0) {
                return new ImportResult(0, 0, List.of("No 'Summary' or 'Title' column found. Headers: " + String.join(", ", headers)));
            }

            int imported = 0;
            var errors = new java.util.ArrayList<String>();
            for (int i = 1; i < lines.size(); i++) {
                try {
                    String[] cols = parseCsvLine(lines.get(i));
                    if (cols.length == 0 || (cols.length == 1 && cols[0].isBlank())) continue;
                    TicketDoc ticket = buildTicket(cols, colMap, projectId);
                    ticketRepo.save(ticket);
                    imported++;
                } catch (Exception e) {
                    errors.add("Row " + (i + 1) + ": " + e.getMessage());
                }
            }
            return new ImportResult(imported, lines.size() - 1, errors);
        }

        private ImportResult importExcel(java.nio.file.Path path, String projectId) throws Exception {
            var errors = new java.util.ArrayList<String>();
            int imported = 0, totalRows = 0;

            try (var workbook = org.apache.poi.ss.usermodel.WorkbookFactory.create(path.toFile())) {
                var sheet = workbook.getSheetAt(0);
                var headerRow = sheet.getRow(0);
                if (headerRow == null) return new ImportResult(0, 0, List.of("Excel sheet has no header row"));

                String[] headers = new String[headerRow.getLastCellNum()];
                for (int c = 0; c < headers.length; c++) {
                    var cell = headerRow.getCell(c);
                    headers[c] = cell != null ? getCellString(cell) : "";
                }
                var colMap = mapColumns(headers);
                if (colMap.get("title") < 0) {
                    return new ImportResult(0, 0, List.of("No 'Summary' or 'Title' column found. Headers: " + String.join(", ", headers)));
                }

                for (int r = 1; r <= sheet.getLastRowNum(); r++) {
                    var row = sheet.getRow(r);
                    if (row == null) continue;
                    totalRows++;
                    try {
                        String[] cols = new String[row.getLastCellNum()];
                        for (int c = 0; c < cols.length; c++) {
                            var cell = row.getCell(c);
                            cols[c] = cell != null ? getCellString(cell) : "";
                        }
                        if (cols.length == 0 || (cols.length > 0 && cols[0].isBlank() && getCol(cols, colMap.get("title")).isBlank())) continue;
                        TicketDoc ticket = buildTicket(cols, colMap, projectId);
                        ticketRepo.save(ticket);
                        imported++;
                    } catch (Exception e) {
                        errors.add("Row " + (r + 1) + ": " + e.getMessage());
                    }
                }
            }
            return new ImportResult(imported, totalRows, errors);
        }

        private String getCellString(org.apache.poi.ss.usermodel.Cell cell) {
            return switch (cell.getCellType()) {
                case STRING -> cell.getStringCellValue();
                case NUMERIC -> {
                    if (org.apache.poi.ss.usermodel.DateUtil.isCellDateFormatted(cell))
                        yield cell.getLocalDateTimeCellValue().toString();
                    double d = cell.getNumericCellValue();
                    yield d == Math.floor(d) ? String.valueOf((long) d) : String.valueOf(d);
                }
                case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
                case FORMULA -> cell.getCellFormula();
                default -> "";
            };
        }

        /** Map common Jira CSV/Excel column names to our ticket fields */
        private Map<String, Integer> mapColumns(String[] headers) {
            Map<String, Integer> map = new java.util.HashMap<>();
            map.put("title", -1);
            map.put("description", -1);
            map.put("priority", -1);
            map.put("status", -1);
            map.put("key", -1);

            for (int i = 0; i < headers.length; i++) {
                String h = headers[i].trim().toLowerCase();
                if (h.equals("summary") || h.equals("title") || h.equals("issue summary")) map.put("title", i);
                else if (h.equals("description") || h.equals("issue description")) map.put("description", i);
                else if (h.equals("priority")) map.put("priority", i);
                else if (h.equals("status")) map.put("status", i);
                else if (h.equals("issue key") || h.equals("key") || h.equals("issue id")) map.put("key", i);
            }
            return map;
        }

        private TicketDoc buildTicket(String[] cols, Map<String, Integer> colMap, String projectId) {
            TicketDoc t = new TicketDoc();
            t.ticketId = UUID.randomUUID().toString();
            t.projectId = projectId;
            t.title = getCol(cols, colMap.get("title"));
            if (t.title.isBlank()) throw new RuntimeException("Empty title");
            t.description = getCol(cols, colMap.get("description"));

            // Map Jira key as prefix to title if present
            String key = getCol(cols, colMap.get("key"));
            if (!key.isBlank()) t.title = "[" + key + "] " + t.title;

            // Map priority
            String prio = getCol(cols, colMap.get("priority")).toUpperCase();
            t.priority = switch (prio) {
                case "HIGHEST", "BLOCKER", "CRITICAL" -> TicketPriority.CRITICAL;
                case "HIGH", "MAJOR" -> TicketPriority.HIGH;
                case "LOW", "MINOR" -> TicketPriority.LOW;
                case "LOWEST", "TRIVIAL" -> TicketPriority.LOW;
                default -> TicketPriority.MEDIUM;
            };

            // Map status
            String st = getCol(cols, colMap.get("status")).toUpperCase().replace(" ", "_");
            t.status = switch (st) {
                case "IN_PROGRESS", "IN_REVIEW", "IN_DEVELOPMENT" -> TicketStatus.IN_PROGRESS;
                case "DONE", "RESOLVED", "CLOSED", "RELEASED" -> TicketStatus.DONE;
                default -> TicketStatus.OPEN;
            };

            t.createdAt = Instant.now();
            t.updatedAt = Instant.now();
            return t;
        }

        private String getCol(String[] cols, int idx) {
            if (idx < 0 || idx >= cols.length) return "";
            return cols[idx] != null ? cols[idx].trim() : "";
        }

        /** Simple CSV line parser (handles quoted fields with commas) */
        private String[] parseCsvLine(String line) {
            var fields = new java.util.ArrayList<String>();
            boolean inQuotes = false;
            var sb = new StringBuilder();
            for (int i = 0; i < line.length(); i++) {
                char c = line.charAt(i);
                if (c == '"') { inQuotes = !inQuotes; continue; }
                if (c == ',' && !inQuotes) { fields.add(sb.toString()); sb.setLength(0); continue; }
                sb.append(c);
            }
            fields.add(sb.toString());
            return fields.toArray(new String[0]);
        }

        record ImportResult(int imported, int total, List<String> errors) {}
    }

    // =======================================================================
    // WEBSOCKET HANDLER
    // =======================================================================

    @Component
    static class JavaClawWsHandler extends TextWebSocketHandler implements EventStreamListener {
        private final ObjectMapper mapper;
        private final EventTailer tailer;
        private final Map<String, Set<WebSocketSession>> subs = new ConcurrentHashMap<>();

        JavaClawWsHandler(ObjectMapper mapper, EventTailer tailer) { this.mapper = mapper; this.tailer = tailer; }

        @PostConstruct void init() { tailer.addListener(this); }

        @Override public void afterConnectionEstablished(WebSocketSession s) {}
        @Override public void afterConnectionClosed(WebSocketSession s, CloseStatus st) {
            subs.values().forEach(set -> set.remove(s));
        }

        @Override protected void handleTextMessage(WebSocketSession session, TextMessage msg) throws Exception {
            var node = mapper.readTree(msg.getPayload());
            String type = node.path("type").asText();
            String sid = node.path("sessionId").asText();
            if ("SUBSCRIBE_SESSION".equals(type)) {
                subs.computeIfAbsent(sid, k -> new CopyOnWriteArraySet<>()).add(session);
                session.sendMessage(new TextMessage(mapper.writeValueAsString(Map.of("type", "SUBSCRIBED", "sessionId", sid))));
            } else if ("UNSUBSCRIBE".equals(type)) {
                var set = subs.get(sid); if (set != null) set.remove(session);
            }
        }

        @Override public void onEvent(EventDoc event) {
            var subscribers = subs.get(event.sessionId);
            if (subscribers == null || subscribers.isEmpty()) return;
            try {
                String json = mapper.writeValueAsString(Map.of(
                    "type", "EVENT", "sessionId", event.sessionId,
                    "payload", Map.of("eventId", event.eventId, "type", event.type,
                        "payload", event.payload != null ? event.payload : "",
                        "timestamp", event.timestamp.toString(), "seq", event.seq)));
                TextMessage tm = new TextMessage(json);
                for (var ws : subscribers) {
                    if (ws.isOpen()) try { ws.sendMessage(tm); } catch (IOException ignored) {}
                }
            } catch (Exception ignored) {}
        }
    }

    // =======================================================================
    // REST CONTROLLERS
    // =======================================================================

    @RestController
    @RequestMapping("/api/sessions")
    static class SessionController {
        private final SessionRepo sessionRepo;
        private final ThreadRepo threadRepo;
        private final MessageRepo messageRepo;
        private final AgentLoop agentLoop;
        private final EventService eventService;

        SessionController(SessionRepo sessionRepo, ThreadRepo threadRepo, MessageRepo messageRepo,
                          AgentLoop agentLoop, EventService eventService) {
            this.sessionRepo = sessionRepo; this.threadRepo = threadRepo;
            this.messageRepo = messageRepo; this.agentLoop = agentLoop; this.eventService = eventService;
        }

        @PostMapping ResponseEntity<?> create(@RequestBody(required = false) CreateSessionRequest req) {
            if (req == null) req = new CreateSessionRequest(null, null, null, null);
            SessionDoc doc = new SessionDoc();
            doc.sessionId = UUID.randomUUID().toString(); doc.createdAt = Instant.now();
            doc.updatedAt = Instant.now(); doc.status = SessionStatus.IDLE;
            doc.threadId = req.threadId();
            doc.modelConfig = req.modelConfig() != null ? req.modelConfig() : ModelConfig.defaults();
            doc.toolPolicy = req.toolPolicy() != null ? req.toolPolicy() : ToolPolicy.allowAll();
            doc.metadata = req.metadata() != null ? req.metadata() : Map.of();
            sessionRepo.save(doc);
            var result = new java.util.LinkedHashMap<String, Object>();
            result.put("sessionId", doc.sessionId); result.put("threadId", doc.threadId);
            result.put("status", doc.status); result.put("createdAt", doc.createdAt);
            result.put("modelConfig", doc.modelConfig);
            return ResponseEntity.ok(result);
        }

        @GetMapping("/{id}") ResponseEntity<?> get(@PathVariable String id) {
            return sessionRepo.findById(id).map(ResponseEntity::ok).orElse(ResponseEntity.notFound().build());
        }

        @GetMapping List<?> list(
                @RequestParam(required = false) String threadId,
                @RequestParam(required = false, defaultValue = "false") boolean standalone) {
            if (threadId != null) return sessionRepo.findByThreadId(threadId);
            if (standalone) return sessionRepo.findByThreadIdIsNullOrderByUpdatedAtDesc();
            return sessionRepo.findAllByOrderByUpdatedAtDesc();
        }

        @PostMapping("/{id}/messages") ResponseEntity<?> sendMessage(@PathVariable String id, @RequestBody SendMessageRequest req) {
            if (sessionRepo.findById(id).isEmpty() && threadRepo.findById(id).isEmpty())
                return ResponseEntity.status(404).body(Map.of("error", "not found"));
            long seq = messageRepo.countBySessionId(id) + 1;
            MessageDoc msg = new MessageDoc();
            msg.messageId = UUID.randomUUID().toString(); msg.sessionId = id; msg.seq = seq;
            msg.role = req.role() != null ? req.role() : "user"; msg.content = req.content(); msg.timestamp = Instant.now();
            messageRepo.save(msg);
            eventService.emit(id, EventType.USER_MESSAGE_RECEIVED, Map.of("content", req.content(), "role", msg.role));
            return ResponseEntity.ok(Map.of("status", "accepted", "messageId", msg.messageId));
        }

        @GetMapping("/{id}/messages") List<?> messages(@PathVariable String id) {
            return messageRepo.findBySessionIdOrderBySeqAsc(id);
        }

        @PostMapping("/{id}/run") ResponseEntity<?> run(@PathVariable String id) {
            if (sessionRepo.findById(id).isEmpty() && threadRepo.findById(id).isEmpty())
                return ResponseEntity.status(404).body(Map.of("error", "not found"));
            agentLoop.startAsync(id);
            return ResponseEntity.ok(Map.of("status", "accepted"));
        }

        @PostMapping("/{id}/pause") ResponseEntity<?> pause(@PathVariable String id) {
            agentLoop.stop(id);
            sessionRepo.findById(id).ifPresent(s -> {
                s.status = SessionStatus.PAUSED; s.updatedAt = Instant.now(); sessionRepo.save(s);
                eventService.emit(id, EventType.SESSION_STATUS_CHANGED, Map.of("status", "PAUSED"));
            });
            return ResponseEntity.ok(Map.of("status", "accepted"));
        }

        @PostMapping("/{id}/resume") ResponseEntity<?> resume(@PathVariable String id) {
            if (sessionRepo.findById(id).isEmpty()) return ResponseEntity.status(404).body(Map.of("error", "not found"));
            agentLoop.startAsync(id);
            return ResponseEntity.ok(Map.of("status", "accepted"));
        }
    }

    // -----------------------------------------------------------------------
    // Project Controller
    // -----------------------------------------------------------------------

    @RestController
    @RequestMapping("/api/projects")
    static class ProjectController {
        private final ProjectRepo projectRepo;

        ProjectController(ProjectRepo projectRepo) { this.projectRepo = projectRepo; }

        @GetMapping List<?> list() { return projectRepo.findAllByOrderByUpdatedAtDesc(); }

        @PostMapping ResponseEntity<?> create(@RequestBody CreateProjectRequest req) {
            ProjectDoc doc = new ProjectDoc();
            doc.projectId = UUID.randomUUID().toString();
            doc.name = req.name();
            doc.description = req.description();
            doc.createdAt = Instant.now();
            doc.updatedAt = Instant.now();
            projectRepo.save(doc);
            return ResponseEntity.ok(doc);
        }

        @GetMapping("/{id}") ResponseEntity<?> get(@PathVariable String id) {
            var opt = projectRepo.findById(id);
            if (opt.isEmpty()) return ResponseEntity.status(404).body(Map.of("error", "not found"));
            return ResponseEntity.ok(opt.get());
        }

        @DeleteMapping("/{id}") ResponseEntity<?> delete(@PathVariable String id) {
            projectRepo.deleteById(id);
            return ResponseEntity.ok(Map.of("status", "deleted"));
        }
    }

    // -----------------------------------------------------------------------
    // Thread Controller (nested under projects)
    // -----------------------------------------------------------------------

    @RestController
    @RequestMapping("/api/projects/{projectId}/threads")
    static class ThreadController {
        private final ThreadRepo threadRepo;
        private final MessageRepo messageRepo;
        private final AgentLoop agentLoop;
        private final EventService eventService;

        ThreadController(ThreadRepo threadRepo, MessageRepo messageRepo,
                         AgentLoop agentLoop, EventService eventService) {
            this.threadRepo = threadRepo; this.messageRepo = messageRepo;
            this.agentLoop = agentLoop; this.eventService = eventService;
        }

        @GetMapping List<?> list(@PathVariable String projectId) {
            return threadRepo.findByProjectIdsOrderByUpdatedAtDesc(projectId);
        }

        @PostMapping ResponseEntity<?> create(@PathVariable String projectId, @RequestBody CreateThreadRequest req) {
            ThreadDoc doc = new ThreadDoc();
            doc.threadId = UUID.randomUUID().toString();
            doc.projectIds = List.of(projectId);
            doc.title = req.title();
            doc.status = SessionStatus.IDLE;
            doc.createdAt = Instant.now();
            doc.updatedAt = Instant.now();
            threadRepo.save(doc);
            return ResponseEntity.ok(doc);
        }

        @PostMapping("/{threadId}/messages") ResponseEntity<?> sendMessage(
                @PathVariable String projectId, @PathVariable String threadId,
                @RequestBody SendMessageRequest req) {
            if (threadRepo.findById(threadId).isEmpty())
                return ResponseEntity.status(404).body(Map.of("error", "thread not found"));
            long seq = messageRepo.countBySessionId(threadId) + 1;
            MessageDoc msg = new MessageDoc();
            msg.messageId = UUID.randomUUID().toString();
            msg.sessionId = threadId; // threadId = sessionId for messages
            msg.seq = seq;
            msg.role = req.role() != null ? req.role() : "user";
            msg.content = req.content();
            msg.timestamp = Instant.now();
            messageRepo.save(msg);
            eventService.emit(threadId, EventType.USER_MESSAGE_RECEIVED,
                    Map.of("content", req.content(), "role", msg.role));
            return ResponseEntity.ok(Map.of("status", "accepted", "messageId", msg.messageId));
        }

        @PostMapping("/{threadId}/run") ResponseEntity<?> run(
                @PathVariable String projectId, @PathVariable String threadId) {
            if (threadRepo.findById(threadId).isEmpty())
                return ResponseEntity.status(404).body(Map.of("error", "thread not found"));
            agentLoop.startAsync(threadId);
            return ResponseEntity.ok(Map.of("status", "accepted"));
        }

        @PostMapping("/{threadId}/pause") ResponseEntity<?> pause(
                @PathVariable String projectId, @PathVariable String threadId) {
            agentLoop.stop(threadId);
            threadRepo.findById(threadId).ifPresent(t -> {
                t.status = SessionStatus.PAUSED; t.updatedAt = Instant.now(); threadRepo.save(t);
                eventService.emit(threadId, EventType.SESSION_STATUS_CHANGED, Map.of("status", "PAUSED"));
            });
            return ResponseEntity.ok(Map.of("status", "accepted"));
        }

        @GetMapping("/{threadId}") ResponseEntity<?> get(
                @PathVariable String projectId, @PathVariable String threadId) {
            return threadRepo.findById(threadId)
                    .filter(t -> t.getEffectiveProjectIds().contains(projectId))
                    .<ResponseEntity<?>>map(ResponseEntity::ok)
                    .orElse(ResponseEntity.status(404).body(Map.of("error", "thread not found")));
        }

        @DeleteMapping("/{threadId}") ResponseEntity<?> delete(
                @PathVariable String projectId, @PathVariable String threadId) {
            if (threadRepo.findById(threadId).isEmpty())
                return ResponseEntity.status(404).body(Map.of("error", "thread not found"));
            agentLoop.stop(threadId);
            messageRepo.deleteBySessionId(threadId);
            threadRepo.deleteById(threadId);
            return ResponseEntity.ok(Map.of("status", "deleted"));
        }
    }

    // -----------------------------------------------------------------------
    // Agent Controller
    // -----------------------------------------------------------------------

    @RestController
    @RequestMapping("/api/agents")
    static class AgentController {
        private final AgentRepo agentRepo;
        AgentController(AgentRepo agentRepo) { this.agentRepo = agentRepo; }

        @GetMapping List<?> list() { return agentRepo.findAll(); }
    }

    // -----------------------------------------------------------------------
    // Tool Controller (static list of available tools)
    // -----------------------------------------------------------------------

    @RestController
    @RequestMapping("/api/tools")
    static class ToolController {
        @GetMapping List<?> list() {
            return List.of(
                Map.of("name", "read_file", "description", "Read a file from the filesystem",
                        "riskProfile", "READ_ONLY"),
                Map.of("name", "write_file", "description", "Write content to a file",
                        "riskProfile", "WRITE_FILES"),
                Map.of("name", "list_directory", "description", "List files in a directory",
                        "riskProfile", "READ_ONLY"),
                Map.of("name", "search_files", "description", "Search file contents with regex",
                        "riskProfile", "READ_ONLY"),
                Map.of("name", "run_command", "description", "Execute a shell command",
                        "riskProfile", "EXEC_SHELL"),
                Map.of("name", "create_ticket", "description", "Create a project ticket",
                        "riskProfile", "WRITE_FILES"),
                Map.of("name", "create_idea", "description", "Create an idea for the project",
                        "riskProfile", "WRITE_FILES"),
                Map.of("name", "memory", "description", "Store and retrieve long-term memory",
                        "riskProfile", "READ_ONLY"),
                Map.of("name", "web_search", "description", "Search the web for information",
                        "riskProfile", "NETWORK_CALLS"),
                Map.of("name", "excel", "description", "Create and manipulate spreadsheets",
                        "riskProfile", "WRITE_FILES")
            );
        }

        @PostMapping("/{name}/invoke") ResponseEntity<?> invoke(
                @PathVariable String name, @RequestBody Map<String, Object> input) {
            try {
                return switch (name) {
                    case "write_file" -> {
                        String path = (String) input.get("path");
                        String content = (String) input.get("content");
                        java.nio.file.Files.createDirectories(java.nio.file.Path.of(path).getParent());
                        java.nio.file.Files.writeString(java.nio.file.Path.of(path), content);
                        yield ResponseEntity.ok(Map.of("success", true, "path", path, "bytes", content.length()));
                    }
                    case "read_file" -> {
                        String path = (String) input.get("path");
                        String content = java.nio.file.Files.readString(java.nio.file.Path.of(path));
                        yield ResponseEntity.ok(Map.of("success", true, "content", content));
                    }
                    case "list_directory" -> {
                        String path = (String) input.getOrDefault("path", ".");
                        var entries = java.nio.file.Files.list(java.nio.file.Path.of(path))
                                .sorted()
                                .map(p -> Map.of("name", p.getFileName().toString(),
                                        "type", java.nio.file.Files.isDirectory(p) ? "directory" : "file"))
                                .toList();
                        yield ResponseEntity.ok(Map.of("success", true, "entries", entries));
                    }
                    default -> ResponseEntity.status(400).body(Map.of("error", "Tool '" + name + "' not implemented for direct invoke"));
                };
            } catch (Exception e) {
                return ResponseEntity.status(500).body(Map.of("success", false, "error", e.getMessage()));
            }
        }
    }

    // -----------------------------------------------------------------------
    // Idea Controller
    // -----------------------------------------------------------------------

    @RestController
    @RequestMapping("/api/projects/{projectId}/ideas")
    static class IdeaController {
        private final IdeaRepo ideaRepo;
        private final TicketRepo ticketRepo;

        IdeaController(IdeaRepo ideaRepo, TicketRepo ticketRepo) {
            this.ideaRepo = ideaRepo; this.ticketRepo = ticketRepo;
        }

        @GetMapping List<?> list(@PathVariable String projectId) {
            return ideaRepo.findByProjectIdOrderByCreatedAtDesc(projectId);
        }

        @PostMapping ResponseEntity<?> create(@PathVariable String projectId, @RequestBody CreateIdeaRequest req) {
            IdeaDoc doc = new IdeaDoc();
            doc.ideaId = UUID.randomUUID().toString();
            doc.projectId = projectId;
            doc.title = req.title();
            doc.content = req.content();
            doc.promoted = false;
            doc.createdAt = Instant.now();
            ideaRepo.save(doc);
            return ResponseEntity.ok(doc);
        }

        @PostMapping("/{ideaId}/promote") ResponseEntity<?> promote(
                @PathVariable String projectId, @PathVariable String ideaId) {
            var opt = ideaRepo.findById(ideaId);
            if (opt.isEmpty()) return ResponseEntity.status(404).body(Map.of("error", "idea not found"));
            IdeaDoc idea = opt.get();

            TicketDoc ticket = new TicketDoc();
            ticket.ticketId = UUID.randomUUID().toString();
            ticket.projectId = projectId;
            ticket.title = idea.title;
            ticket.description = idea.content;
            ticket.priority = TicketPriority.MEDIUM;
            ticket.status = TicketStatus.OPEN;
            ticket.createdAt = Instant.now();
            ticket.updatedAt = Instant.now();
            ticketRepo.save(ticket);

            idea.promoted = true;
            idea.promotedTicketId = ticket.ticketId;
            ideaRepo.save(idea);
            return ResponseEntity.ok(Map.of("idea", idea, "ticket", ticket));
        }
    }

    // -----------------------------------------------------------------------
    // Ticket Controller
    // -----------------------------------------------------------------------

    @RestController
    @RequestMapping("/api/projects/{projectId}/tickets")
    static class TicketController {
        private final TicketRepo ticketRepo;
        private final JiraImportService jiraImportService;
        TicketController(TicketRepo ticketRepo, JiraImportService jiraImportService) {
            this.ticketRepo = ticketRepo; this.jiraImportService = jiraImportService;
        }

        @GetMapping List<?> list(@PathVariable String projectId) {
            return ticketRepo.findByProjectIdOrderByCreatedAtDesc(projectId);
        }

        @PostMapping ResponseEntity<?> create(@PathVariable String projectId, @RequestBody CreateTicketRequest req) {
            TicketDoc doc = new TicketDoc();
            doc.ticketId = UUID.randomUUID().toString();
            doc.projectId = projectId;
            doc.title = req.title();
            doc.description = req.description();
            doc.priority = req.priority() != null ? TicketPriority.valueOf(req.priority().toUpperCase()) : TicketPriority.MEDIUM;
            doc.status = TicketStatus.OPEN;
            doc.createdAt = Instant.now();
            doc.updatedAt = Instant.now();
            ticketRepo.save(doc);
            return ResponseEntity.ok(doc);
        }

        @PostMapping("/import") ResponseEntity<?> importFromFile(
                @PathVariable String projectId, @RequestBody Map<String, String> req) {
            String filePath = req.get("filePath");
            if (filePath == null || filePath.isBlank())
                return ResponseEntity.badRequest().body(Map.of("error", "filePath is required"));
            var result = jiraImportService.importFile(filePath, projectId);
            return ResponseEntity.ok(Map.of(
                    "imported", result.imported(), "total", result.total(),
                    "errors", result.errors()));
        }
    }

    // -----------------------------------------------------------------------
    // Design Controller
    // -----------------------------------------------------------------------

    @RestController
    @RequestMapping("/api/projects/{projectId}/designs")
    static class DesignController {
        private final DesignRepo designRepo;
        DesignController(DesignRepo designRepo) { this.designRepo = designRepo; }

        @GetMapping List<?> list(@PathVariable String projectId) {
            return designRepo.findByProjectIdOrderByCreatedAtDesc(projectId);
        }

        @PostMapping ResponseEntity<?> create(@PathVariable String projectId, @RequestBody CreateDesignRequest req) {
            DesignDoc doc = new DesignDoc();
            doc.designId = UUID.randomUUID().toString();
            doc.projectId = projectId;
            doc.title = req.title();
            doc.source = req.source();
            doc.createdAt = Instant.now();
            designRepo.save(doc);
            return ResponseEntity.ok(doc);
        }
    }

    // -----------------------------------------------------------------------
    // Scorecard Controller
    // -----------------------------------------------------------------------

    @RestController
    @RequestMapping("/api/projects/{projectId}/scorecard")
    static class ScorecardController {
        private final TicketRepo ticketRepo;
        private final IdeaRepo ideaRepo;
        private final ThreadRepo threadRepo;

        ScorecardController(TicketRepo ticketRepo, IdeaRepo ideaRepo, ThreadRepo threadRepo) {
            this.ticketRepo = ticketRepo; this.ideaRepo = ideaRepo; this.threadRepo = threadRepo;
        }

        @GetMapping Map<String, Object> get(@PathVariable String projectId) {
            var tickets = ticketRepo.findByProjectIdOrderByCreatedAtDesc(projectId);
            var ideas = ideaRepo.findByProjectIdOrderByCreatedAtDesc(projectId);
            var threads = threadRepo.findByProjectIdsOrderByUpdatedAtDesc(projectId);

            long open = tickets.stream().filter(t -> t.status == TicketStatus.OPEN).count();
            long done = tickets.stream().filter(t -> t.status == TicketStatus.DONE).count();
            String health = tickets.isEmpty() ? "NEW" : (done > open ? "HEALTHY" : "NEEDS_ATTENTION");

            return Map.of(
                "health", health,
                "metrics", Map.of(
                    "totalTickets", tickets.size(),
                    "openTickets", open,
                    "doneTickets", done,
                    "totalIdeas", ideas.size(),
                    "activeThreads", threads.size()
                ),
                "updatedAt", Instant.now().toString()
            );
        }
    }

    // -----------------------------------------------------------------------
    // Plan Controller
    // -----------------------------------------------------------------------

    @RestController
    @RequestMapping("/api/projects/{projectId}/plans")
    static class PlanController {
        @GetMapping List<?> list(@PathVariable String projectId) {
            // Plans are generated on-demand; return empty for now
            return List.of();
        }
    }

    // -----------------------------------------------------------------------
    // Config Controller
    // -----------------------------------------------------------------------

    @RestController
    @RequestMapping("/api/config")
    static class ConfigController {
        private static int fontSize = 13;
        static String anthropicKey = System.getenv("ANTHROPIC_API_KEY");
        static String openaiKey = System.getenv("OPENAI_API_KEY");

        @GetMapping("/font-size") Map<String, Object> getFontSize() {
            return Map.of("fontSize", fontSize);
        }

        @PostMapping("/font-size") Map<String, Object> setFontSize(@RequestBody Map<String, Integer> body) {
            fontSize = body.getOrDefault("fontSize", 13);
            return Map.of("fontSize", fontSize);
        }

        @PostMapping("/keys") ResponseEntity<?> setKeys(@RequestBody SetApiKeysRequest req) {
            if (req.anthropicKey() != null) anthropicKey = req.anthropicKey();
            if (req.openaiKey() != null) openaiKey = req.openaiKey();
            return ResponseEntity.ok(Map.of("status", "updated"));
        }
    }

    // -----------------------------------------------------------------------
    // Reminder Controller
    // -----------------------------------------------------------------------

    @RestController
    @RequestMapping("/api/reminders")
    static class ReminderController {
        private final ReminderRepo reminderRepo;
        ReminderController(ReminderRepo reminderRepo) { this.reminderRepo = reminderRepo; }

        @GetMapping List<?> list(@RequestParam(required = false) String sessionId) {
            if (sessionId != null) return reminderRepo.findBySessionId(sessionId);
            return reminderRepo.findAll();
        }

        @PostMapping ResponseEntity<?> create(@RequestBody CreateReminderRequest req) {
            ReminderDoc doc = new ReminderDoc();
            doc.reminderId = UUID.randomUUID().toString();
            doc.sessionId = req.sessionId();
            doc.message = req.message();
            doc.recurring = req.recurring();
            doc.intervalMs = req.intervalMs();
            doc.triggerAt = req.triggerAt();
            doc.createdAt = Instant.now();
            reminderRepo.save(doc);
            return ResponseEntity.ok(doc);
        }

        @DeleteMapping("/{id}") ResponseEntity<?> delete(@PathVariable String id) {
            reminderRepo.deleteById(id);
            return ResponseEntity.ok(Map.of("status", "deleted"));
        }
    }

    // -----------------------------------------------------------------------
    // Memory Controller
    // -----------------------------------------------------------------------

    @RestController
    @RequestMapping("/api/memories")
    static class MemoryController {
        private final MemoryRepo memoryRepo;
        MemoryController(MemoryRepo memoryRepo) { this.memoryRepo = memoryRepo; }

        @GetMapping List<?> list(
                @RequestParam(required = false) String scope,
                @RequestParam(required = false) String projectId,
                @RequestParam(required = false) String threadId,
                @RequestParam(required = false) String q) {
            if (q != null && !q.isBlank()) return memoryRepo.searchContent(q);
            if (scope != null) {
                MemoryScope ms = MemoryScope.valueOf(scope.toUpperCase());
                if (threadId != null) return memoryRepo.findByScopeAndThreadId(ms, threadId);
                if (projectId != null) return memoryRepo.findByScopeAndProjectId(ms, projectId);
                return memoryRepo.findByScope(ms);
            }
            return memoryRepo.findAll();
        }

        @PostMapping ResponseEntity<?> create(@RequestBody Map<String, Object> body) {
            MemoryDoc doc = new MemoryDoc();
            doc.memoryId = UUID.randomUUID().toString();
            doc.scope = MemoryScope.valueOf(((String) body.getOrDefault("scope", "GLOBAL")).toUpperCase());
            doc.key = (String) body.get("key");
            doc.content = (String) body.get("content");
            doc.projectId = (String) body.get("projectId");
            doc.sessionId = (String) body.get("sessionId");
            doc.threadId = (String) body.get("threadId");
            doc.createdBy = (String) body.getOrDefault("createdBy", "user");
            if (body.get("tags") instanceof List<?> tags)
                doc.tags = tags.stream().map(Object::toString).toList();
            doc.createdAt = Instant.now();
            doc.updatedAt = Instant.now();
            memoryRepo.save(doc);
            return ResponseEntity.ok(doc);
        }

        @GetMapping("/{id}") ResponseEntity<?> get(@PathVariable String id) {
            var opt = memoryRepo.findById(id);
            if (opt.isEmpty()) return ResponseEntity.status(404).body(Map.of("error", "not found"));
            return ResponseEntity.ok(opt.get());
        }

        @DeleteMapping("/{id}") ResponseEntity<?> delete(@PathVariable String id) {
            memoryRepo.deleteById(id);
            return ResponseEntity.ok(Map.of("status", "deleted"));
        }
    }

    // -----------------------------------------------------------------------
    // Search Response Controller
    // -----------------------------------------------------------------------

    @RestController
    @RequestMapping("/api/search")
    static class SearchController {
        private final EventService eventService;
        private final MessageRepo messageRepo;
        private final AgentLoop agentLoop;

        SearchController(EventService eventService, MessageRepo messageRepo, AgentLoop agentLoop) {
            this.eventService = eventService; this.messageRepo = messageRepo; this.agentLoop = agentLoop;
        }

        @PostMapping("/response") ResponseEntity<?> submitResponse(@RequestBody SubmitSearchResponseRequest req) {
            // 1. Save search results as a user message in the session
            long seq = messageRepo.countBySessionId(req.sessionId()) + 1;
            MessageDoc msg = new MessageDoc();
            msg.messageId = UUID.randomUUID().toString();
            msg.sessionId = req.sessionId();
            msg.seq = seq;
            msg.role = "user";
            msg.content = "[Search Results]\n\n" + req.content();
            msg.timestamp = Instant.now();
            messageRepo.save(msg);

            // 2. Emit event for UI
            eventService.emit(req.sessionId(), EventType.SEARCH_RESPONSE_SUBMITTED,
                    Map.of("requestId", req.requestId(), "contentLength", req.content().length()));

            // 3. Re-run the agent loop so it can process the search results
            agentLoop.startAsync(req.sessionId());

            return ResponseEntity.ok(Map.of("status", "accepted", "messageId", msg.messageId));
        }
    }

    // -----------------------------------------------------------------------
    // Resource Controller
    // -----------------------------------------------------------------------

    @RestController
    @RequestMapping("/api/resources")
    static class ResourceController {
        private final ResourceRepo resourceRepo;
        ResourceController(ResourceRepo resourceRepo) { this.resourceRepo = resourceRepo; }

        @GetMapping List<?> list(@RequestParam(required = false) String projectId) {
            if (projectId != null) return resourceRepo.findByProjectId(projectId);
            return resourceRepo.findAll();
        }

        @PostMapping ResponseEntity<?> create(@RequestBody Map<String, Object> body) {
            ResourceDoc doc = new ResourceDoc();
            doc.resourceId = UUID.randomUUID().toString();
            doc.name = (String) body.get("name");
            doc.email = (String) body.get("email");
            doc.role = (String) body.getOrDefault("role", "ENGINEER");
            doc.skills = body.containsKey("skills") ? (List<String>) body.get("skills") : List.of();
            doc.availability = body.containsKey("availability") ? ((Number) body.get("availability")).doubleValue() : 1.0;
            doc.projectId = (String) body.get("projectId");
            doc.createdAt = Instant.now();
            doc.updatedAt = Instant.now();
            resourceRepo.save(doc);
            return ResponseEntity.ok(doc);
        }

        @GetMapping("/{id}") ResponseEntity<?> get(@PathVariable String id) {
            return resourceRepo.findById(id)
                    .<ResponseEntity<?>>map(ResponseEntity::ok)
                    .orElse(ResponseEntity.status(404).body(Map.of("error", "not found")));
        }

        @DeleteMapping("/{id}") ResponseEntity<?> delete(@PathVariable String id) {
            resourceRepo.deleteById(id);
            return ResponseEntity.ok(Map.of("status", "deleted"));
        }
    }

    // -----------------------------------------------------------------------
    // Log Controller
    // -----------------------------------------------------------------------

    @RestController
    @RequestMapping("/api/logs")
    static class LogController {
        private final EventRepo eventRepo;
        LogController(EventRepo eventRepo) { this.eventRepo = eventRepo; }

        @GetMapping List<?> list(@RequestParam(defaultValue = "50") int limit) {
            return eventRepo.findAll(Sort.by(Sort.Direction.DESC, "timestamp"))
                    .stream().limit(limit).toList();
        }

        @GetMapping("/errors") List<?> errors() {
            return eventRepo.findAll().stream()
                    .filter(e -> e.type == EventType.ERROR)
                    .toList();
        }

        @GetMapping("/llm-interactions") List<?> llmInteractions() {
            return eventRepo.findAll().stream()
                    .filter(e -> e.type == EventType.MODEL_TOKEN_DELTA
                            || e.type == EventType.AGENT_STEP_STARTED
                            || e.type == EventType.AGENT_STEP_COMPLETED)
                    .toList();
        }

        @GetMapping("/llm-interactions/metrics") Map<String, Object> metrics() {
            var all = eventRepo.findAll();
            long steps = all.stream().filter(e -> e.type == EventType.AGENT_STEP_COMPLETED).count();
            long tokens = all.stream().filter(e -> e.type == EventType.MODEL_TOKEN_DELTA).count();
            return Map.of("totalSteps", steps, "totalTokens", tokens,
                    "totalEvents", all.size());
        }
    }

    // -----------------------------------------------------------------------
    // Spec Controller
    // -----------------------------------------------------------------------

    @RestController
    @RequestMapping("/api/specs")
    static class SpecController {
        private final SpecRepo specRepo;
        private final ObjectMapper mapper;

        SpecController(SpecRepo specRepo, ObjectMapper mapper) { this.specRepo = specRepo; this.mapper = mapper; }

        @GetMapping List<?> list(@RequestParam(required = false) String tag, @RequestParam(required = false) String q) {
            if (tag != null && q != null) return specRepo.findByTagsContainingAndTitleContainingIgnoreCase(tag, q);
            if (tag != null) return specRepo.findByTagsContaining(tag);
            if (q != null) return specRepo.searchByText(q);
            return specRepo.findAll();
        }

        @GetMapping("/{id}") ResponseEntity<?> get(@PathVariable String id) {
            return specRepo.findById(id).map(ResponseEntity::ok).orElse(ResponseEntity.notFound().build());
        }

        @PostMapping ResponseEntity<?> create(@RequestBody CreateSpecRequest req) {
            SpecDoc doc = new SpecDoc();
            doc.specId = UUID.randomUUID().toString(); doc.title = req.title(); doc.tags = req.tags();
            doc.source = req.source() != null ? req.source() : "editor";
            doc.jsonBody = req.jsonBody(); doc.createdAt = Instant.now(); doc.updatedAt = Instant.now();
            specRepo.save(doc);
            return ResponseEntity.ok(doc);
        }

        @PutMapping("/{id}") ResponseEntity<?> update(@PathVariable String id, @RequestBody CreateSpecRequest req) {
            return specRepo.findById(id).map(doc -> {
                doc.title = req.title(); doc.tags = req.tags();
                if (req.source() != null) doc.source = req.source();
                doc.jsonBody = req.jsonBody(); doc.updatedAt = Instant.now();
                specRepo.save(doc);
                return ResponseEntity.ok(doc);
            }).orElse(ResponseEntity.notFound().build());
        }

        @GetMapping("/{id}/download") ResponseEntity<?> download(@PathVariable String id) {
            return specRepo.findById(id).map(doc -> {
                try {
                    byte[] json = mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(doc.jsonBody);
                    return ResponseEntity.ok()
                        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + doc.title + ".json\"")
                        .contentType(MediaType.APPLICATION_JSON).body(json);
                } catch (Exception e) { return ResponseEntity.internalServerError().build(); }
            }).orElse(ResponseEntity.notFound().build());
        }
    }
}
