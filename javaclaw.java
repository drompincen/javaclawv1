///usr/bin/env jbang "$0" "$@" ; exit $?

//JAVA 21+

//DEPS org.springframework.boot:spring-boot-starter-web:3.2.5
//DEPS org.springframework.boot:spring-boot-starter-websocket:3.2.5
//DEPS org.springframework.boot:spring-boot-starter-data-mongodb:3.2.5
//DEPS org.springframework.boot:spring-boot-starter-data-mongodb-reactive:3.2.5
//DEPS com.fasterxml.jackson.core:jackson-databind:2.17.0
//DEPS com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.17.0

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
        RESOURCE_ASSIGNED, MEMORY_STORED, MEMORY_RECALLED, MEMORY_DISTILLED
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
    }

    interface ThreadRepo extends MongoRepository<ThreadDoc, String> {
        List<ThreadDoc> findByProjectIdsOrderByUpdatedAtDesc(String projectId);
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
                seedIfMissing(ids, "controller", AgentRole.CONTROLLER, "Routes tasks to specialist agents",
                        List.of("delegation", "routing", "task_analysis"));
                seedIfMissing(ids, "coder", AgentRole.SPECIALIST, "Handles coding tasks: implementation, debugging, testing",
                        List.of("code_analysis", "code_generation", "debugging", "testing"));
                seedIfMissing(ids, "reviewer", AgentRole.CHECKER, "Reviews specialist output for quality and correctness",
                        List.of("review", "quality_check", "validation"));
                seedIfMissing(ids, "pm", AgentRole.SPECIALIST, "Project management: planning, tickets, milestones, resources",
                        List.of("project_management", "sprint_planning", "ticket_management", "resource_planning"));
                seedIfMissing(ids, "distiller", AgentRole.SPECIALIST, "Distills completed sessions into persistent memories",
                        List.of("memory_extraction", "summarization", "knowledge_distillation"));
                return;
            }
            System.out.println("  Seeding default agents...");
            seed("controller", AgentRole.CONTROLLER, "Routes tasks to specialist agents",
                    List.of("delegation", "routing", "task_analysis"));
            seed("coder", AgentRole.SPECIALIST, "Handles coding tasks: implementation, debugging, testing",
                    List.of("code_analysis", "code_generation", "debugging", "testing"));
            seed("reviewer", AgentRole.CHECKER, "Reviews specialist output for quality and correctness",
                    List.of("review", "quality_check", "validation"));
            seed("pm", AgentRole.SPECIALIST, "Project management: planning, tickets, milestones, resources",
                    List.of("project_management", "sprint_planning", "ticket_management", "resource_planning"));
            seed("distiller", AgentRole.SPECIALIST, "Distills completed sessions into persistent memories",
                    List.of("memory_extraction", "summarization", "knowledge_distillation"));
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

        AgentLoop(SessionRepo sessionRepo, ThreadRepo threadRepo, MessageRepo messageRepo,
                  EventService eventService, CheckpointService cpService,
                  LockService lockService, ObjectMapper mapper) {
            this.sessionRepo = sessionRepo; this.threadRepo = threadRepo;
            this.messageRepo = messageRepo; this.eventService = eventService;
            this.cpService = cpService; this.lockService = lockService; this.mapper = mapper;
        }

        @org.springframework.beans.factory.annotation.Autowired
        void setDistillerService(DistillerService distillerService) {
            this.distillerService = distillerService;
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
            try {
                // Dual lookup: session first, then thread
                boolean isThread = false;
                SessionDoc session = sessionRepo.findById(sessionId).orElse(null);
                if (session == null) {
                    ThreadDoc thread = threadRepo.findById(sessionId).orElse(null);
                    if (thread == null) throw new RuntimeException("Session/Thread not found: " + sessionId);
                    isThread = true;
                    // Use thread as the target
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

                // === FAKE LLM: Step 1 — Controller ===
                lockService.renew(sessionId, owner);
                eventService.emit(sessionId, EventType.AGENT_STEP_STARTED,
                        Map.of("step", 1, "agentId", "controller"));
                String delegate = determineDelegate(lastUserMessage);
                String controllerResp = "{\"delegate\": \"" + delegate +
                        "\", \"subTask\": \"Handle: " + truncate(lastUserMessage, 100) + "\"}";
                streamTokens(sessionId, controllerResp);
                eventService.emit(sessionId, EventType.AGENT_STEP_COMPLETED,
                        Map.of("step", 1, "agentId", "controller"));

                Thread.sleep(200);

                // === FAKE LLM: Step 2 — Specialist ===
                if (Thread.currentThread().isInterrupted()) { updateStatus(sessionId, isThread, SessionStatus.PAUSED); return; }
                lockService.renew(sessionId, owner);
                eventService.emit(sessionId, EventType.AGENT_STEP_STARTED,
                        Map.of("step", 2, "agentId", delegate));
                String specialistResp = generateSpecialistResponse(delegate, lastUserMessage, sessionId);
                streamTokens(sessionId, specialistResp);
                eventService.emit(sessionId, EventType.AGENT_STEP_COMPLETED,
                        Map.of("step", 2, "agentId", delegate));

                // Save specialist response as assistant message
                long seq = messageRepo.countBySessionId(sessionId) + 1;
                MessageDoc assistantMsg = new MessageDoc();
                assistantMsg.messageId = UUID.randomUUID().toString();
                assistantMsg.sessionId = sessionId;
                assistantMsg.seq = seq;
                assistantMsg.role = "assistant";
                assistantMsg.content = specialistResp;
                assistantMsg.timestamp = Instant.now();
                messageRepo.save(assistantMsg);

                Thread.sleep(200);

                // === FAKE LLM: Step 3 — Reviewer ===
                if (Thread.currentThread().isInterrupted()) { updateStatus(sessionId, isThread, SessionStatus.PAUSED); return; }
                lockService.renew(sessionId, owner);
                eventService.emit(sessionId, EventType.AGENT_STEP_STARTED,
                        Map.of("step", 3, "agentId", "reviewer"));
                String reviewerResp = "{\"pass\": true, \"summary\": \"Response is comprehensive and addresses the user's request.\"}";
                streamTokens(sessionId, reviewerResp);
                eventService.emit(sessionId, EventType.AGENT_STEP_COMPLETED,
                        Map.of("step", 3, "agentId", "reviewer", "done", true));

                // Checkpoint (use Map to avoid ObjectNode deserialization issues)
                cpService.create(sessionId, 3, Map.of("stepNo", 3, "messageCount", messages.size() + 1));

                updateStatus(sessionId, isThread, SessionStatus.COMPLETED);
                if (distillerService != null) distillerService.distillAsync(sessionId);
            } catch (Exception e) {
                String errMsg = e.getMessage() != null ? e.getMessage() : "Unknown error";
                updateStatus(sessionId, false, SessionStatus.FAILED);
                eventService.emit(sessionId, EventType.ERROR, Map.of("message", errMsg));
            } finally {
                lockService.release(sessionId, owner);
                running.remove(sessionId);
            }
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

        private String determineDelegate(String userMessage) {
            if (userMessage == null || userMessage.isBlank()) return "pm";
            String lower = userMessage.toLowerCase();
            if (lower.contains("code") || lower.contains("bug") || lower.contains("fix")
                    || lower.contains("implement") || lower.contains("write") || lower.contains("debug")
                    || lower.contains("function") || lower.contains("class") || lower.contains("test")) {
                return "coder";
            }
            return "pm";
        }

        private String generateSpecialistResponse(String delegate, String userMessage, String threadId) {
            if (userMessage == null) userMessage = "";
            String lower = userMessage.toLowerCase();
            return switch (delegate) {
                case "coder" -> """
                        I've analyzed the coding request: "%s"

                        **Approach:**
                        1. Reviewed existing codebase structure
                        2. Identified the relevant files and dependencies
                        3. Implemented the requested changes

                        **Changes Made:**
                        - Modified the target files according to requirements
                        - Added appropriate error handling
                        - Ensured backward compatibility

                        **Testing:**
                        - All existing tests continue to pass
                        - Added unit tests for new functionality

                        The implementation is complete. Let me know if you need any adjustments."""
                        .formatted(truncate(userMessage, 100));
                case "pm" -> {
                    if (lower.contains("sprint") || lower.contains("plan")) {
                        yield """
                                **Sprint Planning Summary:**

                                Based on the current project state, here's my recommendation:

                                **Sprint Goal:** Establish project foundations and first deliverable
                                **Duration:** 2 weeks
                                **Capacity:** TBD (please add team members)

                                **Proposed Tickets:**
                                1. [HIGH] Set up project repository and CI/CD pipeline
                                2. [HIGH] Define core data model and API contracts
                                3. [MEDIUM] Implement authentication and authorization
                                4. [MEDIUM] Create initial UI wireframes
                                5. [LOW] Write project README and developer onboarding guide

                                Would you like me to create these as tickets?""";
                    }
                    yield """
                            I've analyzed your request: "%s"

                            **Findings:**
                            - The project is currently in ACTIVE status
                            - No blocking issues identified
                            - Team capacity and timeline need to be established

                            **Recommendations:**
                            1. Clarify the project scope and deliverables
                            2. Set up tracking milestones
                            3. Assign ownership for key workstreams

                            Let me know how you'd like to proceed!"""
                            .formatted(truncate(userMessage, 100));
                }
                default -> "I've processed your request: \"%s\"\n\nTask completed successfully."
                        .formatted(truncate(userMessage, 100));
            };
        }

        private void streamTokens(String sessionId, String response) throws InterruptedException {
            String[] words = response.split("(?<=\\s)");
            for (String word : words) {
                eventService.emit(sessionId, EventType.MODEL_TOKEN_DELTA, Map.of("delta", word));
                Thread.sleep(30);
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
        private final MessageRepo messageRepo;
        private final AgentLoop agentLoop;
        private final EventService eventService;

        SessionController(SessionRepo sessionRepo, MessageRepo messageRepo,
                          AgentLoop agentLoop, EventService eventService) {
            this.sessionRepo = sessionRepo; this.messageRepo = messageRepo;
            this.agentLoop = agentLoop; this.eventService = eventService;
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
            if (sessionRepo.findById(id).isEmpty()) return ResponseEntity.status(404).body(Map.of("error", "not found"));
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
            if (sessionRepo.findById(id).isEmpty()) return ResponseEntity.status(404).body(Map.of("error", "not found"));
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
        TicketController(TicketRepo ticketRepo) { this.ticketRepo = ticketRepo; }

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
        private static String anthropicKey;
        private static String openaiKey;

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
        SearchController(EventService eventService) { this.eventService = eventService; }

        @PostMapping("/response") ResponseEntity<?> submitResponse(@RequestBody SubmitSearchResponseRequest req) {
            eventService.emit(req.sessionId(), EventType.APPROVAL_RESPONDED,
                    Map.of("requestId", req.requestId(), "content", req.content()));
            return ResponseEntity.ok(Map.of("status", "accepted"));
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
