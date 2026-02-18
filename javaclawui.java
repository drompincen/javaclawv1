///usr/bin/env jbang "$0" "$@" ; exit $?

//JAVA 21+

//DEPS com.formdev:flatlaf:3.4
//DEPS com.fasterxml.jackson.core:jackson-databind:2.17.0

// ============================================================================
// JavaClaw Terminal UI — Bloomberg-style JBang Swing client
// ============================================================================
// Usage:
//   jbang javaclawui.java                        # Connect to localhost:8080
//   jbang javaclawui.java --url http://host:9090  # Custom backend URL
//
// Keyboard: F1 for full shortcut reference
// ============================================================================

package javaclawui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.formdev.flatlaf.FlatDarkLaf;

import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.text.*;
import java.awt.*;
import java.awt.datatransfer.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.net.URI;
import java.net.http.*;
import java.nio.file.Files;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;

public class javaclawui {

    static String baseUrl = "http://localhost:8080";
    static final ObjectMapper mapper = new ObjectMapper();
    static Font TERM_FONT;
    static int currentFontSize = 17;
    static final int MIN_FONT_SIZE = 10;
    static final int MAX_FONT_SIZE = 24;
    static final int DEFAULT_FONT_SIZE = 17;
    static final List<Runnable> fontChangeListeners = new ArrayList<>();

    public static void main(String... args) {
        parseArgs(args);
        TERM_FONT = loadTermFont(currentFontSize);
        FlatDarkLaf.setup();
        applyTheme();

        SwingUtilities.invokeLater(() -> {
            JFrame frame = new JFrame("JAVACLAW TERMINAL");
            frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            MainPanel mainPanel = new MainPanel(frame);
            frame.setContentPane(mainPanel);
            frame.setSize(1400, 900);
            frame.setLocationRelativeTo(null);
            frame.setVisible(true);
        });
    }

    static void parseArgs(String[] args) {
        for (int i = 0; i < args.length; i++) {
            if ("--url".equals(args[i]) && i + 1 < args.length) {
                baseUrl = args[++i];
                if (baseUrl.endsWith("/")) baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
            }
        }
    }

    static Font loadTermFont(float size) {
        for (String name : new String[]{"JetBrains Mono", "Fira Code", "Consolas", "Courier New", Font.MONOSPACED}) {
            Font f = new Font(name, Font.PLAIN, (int) size);
            if (!f.getFamily().equals(Font.DIALOG)) return f;
        }
        return new Font(Font.MONOSPACED, Font.PLAIN, (int) size);
    }

    static void applyTheme() {
        UIManager.put("Component.focusWidth", 0);
        UIManager.put("Button.arc", 0);
        UIManager.put("TextComponent.arc", 0);
        UIManager.put("ScrollBar.width", 8);
        UIManager.put("ScrollBar.thumbArc", 0);
        UIManager.put("Panel.background", Theme.BG);
        UIManager.put("List.background", Theme.BG);
        UIManager.put("List.foreground", Theme.FG);
        UIManager.put("List.selectionBackground", Theme.SEL_BG);
        UIManager.put("List.selectionForeground", Theme.BLUE);
        UIManager.put("TextField.background", Theme.BG);
        UIManager.put("TextField.foreground", Theme.FG);
        UIManager.put("TextField.caretForeground", Theme.FG);
        UIManager.put("TextPane.background", Theme.BG);
        UIManager.put("TextPane.foreground", Theme.FG);
        UIManager.put("Table.background", Theme.BG);
        UIManager.put("Table.foreground", Theme.FG);
        UIManager.put("Table.selectionBackground", Theme.SEL_BG);
        UIManager.put("Button.background", Theme.HDR_BG);
        UIManager.put("Button.foreground", Theme.FG);
        UIManager.put("ScrollPane.background", Theme.BG);
        UIManager.put("SplitPane.background", Theme.BG);
        UIManager.put("SplitPaneDivider.draggingColor", Theme.BORDER);
        UIManager.put("TitledBorder.titleColor", Theme.FG);
        UIManager.put("OptionPane.background", Theme.BG);
        UIManager.put("OptionPane.messageForeground", Theme.FG);
    }

    static void updateAllFonts(int newSize) {
        currentFontSize = Math.max(MIN_FONT_SIZE, Math.min(MAX_FONT_SIZE, newSize));
        TERM_FONT = loadTermFont(currentFontSize);
        for (Runnable listener : fontChangeListeners) listener.run();
        // Fire-and-forget save to backend
        CompletableFuture.runAsync(() -> {
            try {
                ObjectNode body = mapper.createObjectNode();
                body.put("fontSize", currentFontSize);
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "/api/config/font-size"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                        .build();
                HttpClient.newHttpClient().send(req, HttpResponse.BodyHandlers.ofString());
            } catch (Exception ignored) {}
        });
    }

    // -----------------------------------------------------------------------
    // Theme
    // -----------------------------------------------------------------------
    static class Theme {
        static final Color BG       = new Color(0x0A, 0x0A, 0x0A);
        static final Color FG       = new Color(0x33, 0xFF, 0x33);
        static final Color DIM      = new Color(0x1A, 0x8C, 0x1A);
        static final Color AMBER    = new Color(0xFF, 0xAA, 0x00);
        static final Color RED      = new Color(0xFF, 0x33, 0x33);
        static final Color CYAN     = new Color(0x00, 0xCC, 0xCC);
        static final Color WHITE    = new Color(0xCC, 0xCC, 0xCC);
        static final Color BLUE     = new Color(0x33, 0x99, 0xFF);
        static final Color BORDER   = new Color(0x1A, 0x3A, 0x1A);
        static final Color SEL_BG   = new Color(0x00, 0x33, 0x00);
        static final Color HDR_BG   = new Color(0x00, 0x1A, 0x00);
    }

    // -----------------------------------------------------------------------
    // HTTP API Client
    // -----------------------------------------------------------------------
    static class ApiClient {
        private final HttpClient client = HttpClient.newHttpClient();

        JsonNode post(String path, ObjectNode body) throws Exception {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + path))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body != null ? body.toString() : "{}"))
                    .build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            return mapper.readTree(resp.body());
        }

        JsonNode get(String path) throws Exception {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + path)).GET().build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            return mapper.readTree(resp.body());
        }

        void delete(String path) throws Exception {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + path)).DELETE().build();
            client.send(req, HttpResponse.BodyHandlers.ofString());
        }

        JsonNode createSession() throws Exception { return post("/api/sessions", mapper.createObjectNode()); }
        JsonNode listSessions() throws Exception { return get("/api/sessions"); }
        JsonNode listTools() throws Exception { return get("/api/tools"); }
        JsonNode listAgents() throws Exception { return get("/api/agents"); }
        JsonNode listReminders(String sid) throws Exception { return get("/api/reminders?sessionId=" + sid); }

        JsonNode sendMessage(String sid, String content) throws Exception {
            ObjectNode body = mapper.createObjectNode();
            body.put("content", content);
            body.put("role", "user");
            return post("/api/sessions/" + sid + "/messages", body);
        }

        JsonNode sendMultimodal(String sid, String text, String imgBase64) throws Exception {
            ObjectNode body = mapper.createObjectNode();
            body.put("role", "user");
            ArrayNode parts = body.putArray("parts");
            if (imgBase64 != null) {
                ObjectNode imgPart = parts.addObject();
                imgPart.put("type", "image");
                imgPart.put("mediaType", "image/png");
                imgPart.put("data", imgBase64);
            }
            if (text != null && !text.isBlank()) {
                ObjectNode textPart = parts.addObject();
                textPart.put("type", "text");
                textPart.put("text", text);
            }
            body.put("content", text != null ? text : "(image)");
            return post("/api/sessions/" + sid + "/messages", body);
        }

        void runSession(String sid) throws Exception { post("/api/sessions/" + sid + "/run", null); }
        void pauseSession(String sid) throws Exception { post("/api/sessions/" + sid + "/pause", null); }
        void deleteReminder(String id) throws Exception { delete("/api/reminders/" + id); }

        JsonNode createReminder(String sid, String msg, boolean recurring, Long interval, String triggerAt) throws Exception {
            ObjectNode body = mapper.createObjectNode();
            body.put("sessionId", sid);
            body.put("message", msg);
            body.put("type", "TIME_BASED");
            body.put("recurring", recurring);
            if (interval != null) body.put("intervalSeconds", interval);
            if (triggerAt != null) body.put("triggerAt", triggerAt);
            return post("/api/reminders", body);
        }

        void setApiKeys(String anthropicKey, String openaiKey) throws Exception {
            ObjectNode body = mapper.createObjectNode();
            if (anthropicKey != null) body.put("anthropicKey", anthropicKey);
            if (openaiKey != null) body.put("openaiKey", openaiKey);
            post("/api/config/keys", body);
        }

        void submitSearchResponse(String sessionId, String requestId, String content) throws Exception {
            ObjectNode body = mapper.createObjectNode();
            body.put("sessionId", sessionId);
            body.put("requestId", requestId);
            body.put("content", content);
            post("/api/search/response", body);
        }

        JsonNode listMemories(String scope, String query) throws Exception {
            StringBuilder path = new StringBuilder("/api/memories?");
            if (scope != null) path.append("scope=").append(scope).append("&");
            if (query != null) path.append("query=").append(java.net.URLEncoder.encode(query, java.nio.charset.StandardCharsets.UTF_8));
            return get(path.toString());
        }

        // Project API
        JsonNode listProjects() throws Exception { return get("/api/projects"); }
        JsonNode createProject(String name, String description) throws Exception {
            ObjectNode body = mapper.createObjectNode();
            body.put("name", name);
            body.put("description", description != null ? description : "");
            return post("/api/projects", body);
        }
        JsonNode getProject(String projectId) throws Exception { return get("/api/projects/" + projectId); }
        void deleteProject(String projectId) throws Exception { delete("/api/projects/" + projectId); }

        // Thread API (project-scoped)
        JsonNode listThreads(String projectId) throws Exception { return get("/api/projects/" + projectId + "/threads"); }
        JsonNode createThread(String projectId, String title) throws Exception {
            ObjectNode body = mapper.createObjectNode();
            body.put("title", title != null ? title : "New Thread");
            return post("/api/projects/" + projectId + "/threads", body);
        }
        JsonNode sendThreadMessage(String projectId, String threadId, String content) throws Exception {
            ObjectNode body = mapper.createObjectNode();
            body.put("content", content);
            body.put("role", "user");
            return post("/api/projects/" + projectId + "/threads/" + threadId + "/messages", body);
        }
        void runThread(String projectId, String threadId) throws Exception {
            post("/api/projects/" + projectId + "/threads/" + threadId + "/run", null);
        }
        void pauseThread(String projectId, String threadId) throws Exception {
            post("/api/projects/" + projectId + "/threads/" + threadId + "/pause", null);
        }

        // Idea API
        JsonNode listIdeas(String projectId) throws Exception { return get("/api/projects/" + projectId + "/ideas"); }
        JsonNode createIdea(String projectId, String title, String content) throws Exception {
            ObjectNode body = mapper.createObjectNode();
            body.put("title", title);
            body.put("content", content != null ? content : "");
            return post("/api/projects/" + projectId + "/ideas", body);
        }
        JsonNode promoteIdea(String projectId, String ideaId) throws Exception {
            return post("/api/projects/" + projectId + "/ideas/" + ideaId + "/promote", null);
        }

        // Ticket API
        JsonNode listTickets(String projectId) throws Exception { return get("/api/projects/" + projectId + "/tickets"); }
        JsonNode createTicket(String projectId, String title, String description, String priority) throws Exception {
            ObjectNode body = mapper.createObjectNode();
            body.put("title", title);
            body.put("description", description != null ? description : "");
            body.put("priority", priority != null ? priority : "MEDIUM");
            return post("/api/projects/" + projectId + "/tickets", body);
        }

        // Design API
        JsonNode listDesigns(String projectId) throws Exception { return get("/api/projects/" + projectId + "/designs"); }
        JsonNode createDesign(String projectId, String title, String source) throws Exception {
            ObjectNode body = mapper.createObjectNode();
            body.put("title", title);
            body.put("source", source != null ? source : "manual");
            return post("/api/projects/" + projectId + "/designs", body);
        }

        // Scorecard API
        JsonNode getScorecard(String projectId) throws Exception { return get("/api/projects/" + projectId + "/scorecard"); }

        // Plan API
        JsonNode listPlans(String projectId) throws Exception { return get("/api/projects/" + projectId + "/plans"); }

        // Log API
        JsonNode listLogs(int limit) throws Exception { return get("/api/logs?limit=" + limit); }
        JsonNode listLogErrors() throws Exception { return get("/api/logs/errors"); }
        JsonNode listLlmInteractions() throws Exception { return get("/api/logs/llm-interactions"); }
        JsonNode llmMetrics() throws Exception { return get("/api/logs/llm-interactions/metrics"); }
    }

    // -----------------------------------------------------------------------
    // WebSocket Event Client
    // -----------------------------------------------------------------------
    static class EventSocket implements java.net.http.WebSocket.Listener {
        private final ChatPanel chatPanel;
        private final AgentPane agentPane;
        private SearchRequestPane searchPane;
        private final StringBuilder buffer = new StringBuilder();
        private java.net.http.WebSocket ws;
        private String subscribedSessionId;

        EventSocket(ChatPanel chatPanel, AgentPane agentPane) {
            this.chatPanel = chatPanel;
            this.agentPane = agentPane;
        }

        void setSearchPane(SearchRequestPane searchPane) { this.searchPane = searchPane; }

        void connect() {
            String wsUrl = baseUrl.replace("http://", "ws://").replace("https://", "wss://") + "/ws";
            HttpClient.newHttpClient().newWebSocketBuilder()
                    .buildAsync(URI.create(wsUrl), this)
                    .thenAccept(w -> this.ws = w)
                    .exceptionally(ex -> {
                        SwingUtilities.invokeLater(() -> chatPanel.appendSystem("WS connect failed: " + ex.getMessage()));
                        return null;
                    });
        }

        void subscribe(String sessionId) {
            if (subscribedSessionId != null && ws != null)
                ws.sendText("{\"type\":\"UNSUBSCRIBE\",\"sessionId\":\"" + subscribedSessionId + "\"}", true);
            subscribedSessionId = sessionId;
            if (ws != null && sessionId != null)
                ws.sendText("{\"type\":\"SUBSCRIBE_SESSION\",\"sessionId\":\"" + sessionId + "\"}", true);
        }

        @Override public CompletionStage<?> onText(java.net.http.WebSocket webSocket, CharSequence data, boolean last) {
            buffer.append(data);
            if (last) { handleMessage(buffer.toString()); buffer.setLength(0); }
            webSocket.request(1);
            return null;
        }

        @Override public CompletionStage<?> onClose(java.net.http.WebSocket webSocket, int code, String reason) {
            SwingUtilities.invokeLater(() -> chatPanel.appendSystem("WS closed. Reconnecting..."));
            CompletableFuture.delayedExecutor(2, TimeUnit.SECONDS).execute(() -> {
                connect();
                if (subscribedSessionId != null)
                    CompletableFuture.delayedExecutor(1, TimeUnit.SECONDS).execute(() -> subscribe(subscribedSessionId));
            });
            return null;
        }

        @Override public void onError(java.net.http.WebSocket webSocket, Throwable error) {
            SwingUtilities.invokeLater(() -> chatPanel.appendSystem("WS error: " + error.getMessage()));
        }

        private void handleMessage(String rawMsg) {
            try {
                JsonNode node = mapper.readTree(rawMsg);
                if (!"EVENT".equals(node.path("type").asText())) return;
                JsonNode payload = node.path("payload");
                String eventType = payload.path("type").asText();
                String agentId = payload.path("payload").path("agentId").asText(null);

                SwingUtilities.invokeLater(() -> {
                    switch (eventType) {
                        case "MODEL_TOKEN_DELTA" -> {
                            String token = payload.path("payload").path("token").asText(payload.path("payload").asText());
                            chatPanel.appendToken(token);
                        }
                        case "AGENT_STEP_STARTED" -> {
                            chatPanel.setCurrentAgent(agentId);
                            agentPane.onStepStarted(agentId);
                        }
                        case "AGENT_STEP_COMPLETED" -> {
                            chatPanel.finishAssistantMessage();
                            // Show metadata if available
                            String api = payload.path("payload").path("apiProvider").asText("unknown");
                            long dur = payload.path("payload").path("durationMs").asLong(0);
                            boolean mocked = payload.path("payload").path("mocked").asBoolean(false);
                            if (agentId != null) chatPanel.showAgentMeta(agentId, api, dur, mocked);
                            agentPane.onStepCompleted(agentId);
                        }
                        case "TOOL_CALL_PROPOSED", "TOOL_CALL_STARTED" -> {
                            String tool = payload.path("payload").path("tool").asText(payload.path("payload").asText());
                            chatPanel.appendSystem("Tool: " + tool);
                            agentPane.onToolCall(tool);
                        }
                        case "TOOL_RESULT" -> {
                            String result = payload.path("payload").path("result").asText(payload.path("payload").asText());
                            if (result.length() > 200) result = result.substring(0, 200) + "...";
                            chatPanel.appendSystem("  Result: " + result);
                        }
                        case "TOOL_STDOUT_DELTA" -> chatPanel.appendSystem("  " + payload.path("payload").path("text").asText());
                        case "SESSION_STATUS_CHANGED" -> {
                            String status = payload.path("payload").path("status").asText(payload.path("payload").asText());
                            chatPanel.appendSystem("Session " + status);
                            agentPane.onStatusChanged(status);
                        }
                        case "AGENT_DELEGATED" -> {
                            String target = payload.path("payload").path("targetAgentId").asText();
                            agentPane.onAgentEvent("Delegating to [" + target + "]");
                        }
                        case "AGENT_SWITCHED" -> {
                            String to = payload.path("payload").path("toAgent").asText();
                            agentPane.onAgentEvent("Switched to [" + to + "]");
                        }
                        case "AGENT_CHECK_PASSED" -> {
                            String summary = payload.path("payload").path("summary").asText();
                            agentPane.onAgentEvent("CHECK PASSED: " + summary);
                            chatPanel.appendSystem("[reviewer] PASS");
                        }
                        case "AGENT_CHECK_FAILED" -> {
                            String feedback = payload.path("payload").path("feedback").asText();
                            agentPane.onAgentEvent("CHECK FAILED: " + feedback);
                            chatPanel.appendError("[reviewer] FAIL: " + feedback);
                        }
                        case "AGENT_RESPONSE" -> {
                            String aid = payload.path("payload").path("agentId").asText("agent");
                            agentPane.onAgentEvent("[" + aid + "] responded");
                        }
                        case "TOOL_PROGRESS" -> {
                            String progressMsg = payload.path("payload").path("message").asText();
                            // Check if this is a search request
                            if (progressMsg != null && progressMsg.startsWith("SEARCH_REQUEST:") && searchPane != null) {
                                String[] pParts = progressMsg.split(":", 4);
                                if (pParts.length >= 4) {
                                    String reqId = pParts[1];
                                    String pUrl = pParts[2];
                                    String pQuery = pParts[3];
                                    searchPane.onSearchRequested(reqId, pQuery, pUrl);
                                }
                            }
                        }
                        case "SEARCH_REQUESTED" -> {
                            if (searchPane != null) {
                                String reqId = payload.path("payload").path("requestId").asText();
                                String query = payload.path("payload").path("query").asText();
                                String url = payload.path("payload").path("searchUrl").asText();
                                searchPane.onSearchRequested(reqId, query, url);
                            }
                        }
                        case "ERROR" -> {
                            String error = payload.path("payload").path("message").asText(payload.path("payload").asText());
                            chatPanel.appendError("ERROR: " + error);
                            agentPane.onError(error);
                        }
                    }
                });
            } catch (Exception ignored) {}
        }
    }

    // -----------------------------------------------------------------------
    // Help Overlay (F1)
    // -----------------------------------------------------------------------
    static final String[][] HELP_ROWS = {
            {"F1", "Toggle this help overlay"},
            {"F2", "Create new project"},
            {"F3", "Run agent on thread/session"},
            {"F4", "Pause agent"},
            {"F5", "Show available tools"},
            {"F6", "Focus project navigator"},
            {"F7 / Ctrl+L", "Focus chat input"},
            {"F8", "Timer / reminder manager"},
            {"F9", "Attach file to message"},
            {"F10", "Toggle agent pane"},
            {"F11", "Toggle search request pane"},
            {"F12", "Show memory browser"},
            {"Ctrl+N", "Create standalone session"},
            {"Ctrl+T", "New thread in current project"},
            {"Ctrl+H", "Show tutorial / help guide"},
            {"Ctrl+=", "Increase font size"},
            {"Ctrl+-", "Decrease font size"},
            {"Ctrl+0", "Reset font size to default"},
            {"Ctrl+V", "Paste image from clipboard"},
            {"Ctrl+K", "Configure API keys"},
            {"Enter", "Send message + auto-run agent"},
            {"Ctrl+R", "Refresh project list"},
            {"Ctrl+W", "Clear chat display"},
            {"Escape", "Close overlay / cancel"},
            {"Up/Down", "Navigate list / input history"},
    };

    static class HelpOverlay extends JComponent {
        boolean shown = false;
        void toggle() { shown = !shown; setVisible(shown); repaint(); }

        @Override protected void paintComponent(Graphics g) {
            if (!shown) return;
            Graphics2D g2 = (Graphics2D) g;
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(new Color(0, 0, 0, 204));
            g2.fillRect(0, 0, getWidth(), getHeight());

            int bw = 580, bh = 620;
            int x = (getWidth() - bw) / 2, y = (getHeight() - bh) / 2;
            g2.setColor(Theme.BG);
            g2.fillRect(x, y, bw, bh);
            g2.setColor(Theme.BORDER);
            g2.drawRect(x, y, bw, bh);

            g2.setColor(Theme.FG);
            g2.setFont(TERM_FONT.deriveFont(Font.BOLD, 16f));
            g2.drawString("JAVACLAW KEYBOARD SHORTCUTS", x + 20, y + 30);

            g2.setFont(TERM_FONT.deriveFont(12f));
            int ly = y + 60;
            for (String[] row : HELP_ROWS) {
                g2.setColor(Theme.BLUE);
                g2.drawString(String.format("%-18s", row[0]), x + 20, ly);
                g2.setColor(Theme.WHITE);
                g2.drawString(row[1], x + 200, ly);
                ly += 22;
            }
            g2.setColor(Theme.DIM);
            g2.drawString("Press F1 or ESC to close", x + 20, y + bh - 20);
        }

        { addMouseListener(new MouseAdapter() { @Override public void mouseClicked(MouseEvent e) { toggle(); } }); }
    }

    // -----------------------------------------------------------------------
    // Main Panel
    // -----------------------------------------------------------------------
    static class MainPanel extends JPanel {
        final ApiClient api = new ApiClient();
        final ChatPanel chatPanel;
        final AgentPane agentPane;
        final ProjectNavigatorPanel sidebar;
        final SearchRequestPane searchPane;
        final EventSocket eventSocket;
        final HelpOverlay helpOverlay;
        final TutorialOverlay tutorialOverlay;
        final JLabel fontSizeLabel;

        MainPanel(JFrame frame) {
            setLayout(new BorderLayout(0, 0));
            setBackground(Theme.BG);

            chatPanel = new ChatPanel(api);
            agentPane = new AgentPane(api);
            searchPane = new SearchRequestPane(api, chatPanel);
            eventSocket = new EventSocket(chatPanel, agentPane);
            eventSocket.setSearchPane(searchPane);
            sidebar = new ProjectNavigatorPanel(api, chatPanel, eventSocket);

            // Wire references for auto-create, auto-subscribe, and agent feedback
            chatPanel.eventSocket = eventSocket;
            chatPanel.agentPane = agentPane;
            chatPanel.sidebar = sidebar;

            // Header
            JPanel header = new JPanel(new BorderLayout());
            header.setBackground(Theme.HDR_BG);
            header.setPreferredSize(new Dimension(0, 26));
            header.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, Theme.BORDER));
            JLabel logo = new JLabel("  JAVACLAW v1.0");
            logo.setFont(TERM_FONT.deriveFont(Font.BOLD, 14f));
            logo.setForeground(Theme.FG);
            header.add(logo, BorderLayout.WEST);
            JPanel headerRight = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 2));
            headerRight.setBackground(Theme.HDR_BG);
            JLabel urlLbl = new JLabel(baseUrl);
            urlLbl.setFont(TERM_FONT.deriveFont(11f));
            urlLbl.setForeground(Theme.DIM);
            headerRight.add(urlLbl);
            fontSizeLabel = new JLabel(currentFontSize + "pt");
            fontSizeLabel.setFont(TERM_FONT.deriveFont(Font.BOLD, 11f));
            fontSizeLabel.setForeground(Theme.AMBER);
            headerRight.add(fontSizeLabel);
            header.add(headerRight, BorderLayout.EAST);
            add(header, BorderLayout.NORTH);

            // Center split: sidebar | chat+searchPane | agentPane
            JPanel chatWrap = new JPanel(new BorderLayout());
            chatWrap.add(chatPanel, BorderLayout.CENTER);
            searchPane.setVisible(false);
            chatWrap.add(searchPane, BorderLayout.SOUTH);

            JSplitPane rightSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, chatWrap, agentPane);
            rightSplit.setDividerLocation(700);
            rightSplit.setResizeWeight(0.75);
            rightSplit.setDividerSize(2);
            rightSplit.setBorder(null);

            JSplitPane mainSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, sidebar, rightSplit);
            mainSplit.setDividerLocation(220);
            mainSplit.setDividerSize(2);
            mainSplit.setBorder(null);
            add(mainSplit, BorderLayout.CENTER);

            // Status bar
            JPanel statusBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 2));
            statusBar.setBackground(Theme.HDR_BG);
            statusBar.setPreferredSize(new Dimension(0, 22));
            statusBar.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, Theme.BORDER));
            for (String s : new String[]{"F1:Help", "F2:Project", "F3:Run", "F4:Pause", "F5:Tools",
                    "F6:Nav", "F7:Chat", "F8:Timer", "F9:File", "F10:Agents", "F11:Search",
                    "F12:Memory", "^K:Keys", "^H:Tutorial", "^+/-:Font"}) {
                JLabel l = new JLabel(s);
                l.setFont(TERM_FONT.deriveFont(10f));
                l.setForeground(Theme.DIM);
                statusBar.add(l);
            }
            add(statusBar, BorderLayout.SOUTH);

            // Help overlay as glass pane
            helpOverlay = new HelpOverlay();
            helpOverlay.setVisible(false);

            // Tutorial overlay
            tutorialOverlay = new TutorialOverlay();
            tutorialOverlay.setVisible(false);

            // Use a layered glass pane to hold both overlays
            JPanel glass = new JPanel(null) {
                @Override public boolean isOptimizedDrawingEnabled() { return false; }
            };
            glass.setOpaque(false);
            glass.setLayout(new OverlayLayout(glass));
            glass.add(helpOverlay);
            glass.add(tutorialOverlay);
            frame.setGlassPane(glass);
            glass.setVisible(true);

            // Font change listener for header
            fontChangeListeners.add(() -> {
                logo.setFont(TERM_FONT.deriveFont(Font.BOLD, 14f));
                urlLbl.setFont(TERM_FONT.deriveFont(11f));
                fontSizeLabel.setText(currentFontSize + "pt");
                fontSizeLabel.setFont(TERM_FONT.deriveFont(Font.BOLD, 11f));
                frame.repaint();
            });

            // Keyboard shortcuts
            registerShortcuts(frame);

            // Connect
            eventSocket.connect();
            sidebar.refresh();
            agentPane.refreshAgents();

            // Load font size from backend
            CompletableFuture.runAsync(() -> {
                try {
                    JsonNode fs = api.get("/api/config/font-size");
                    int size = fs.path("fontSize").asInt(DEFAULT_FONT_SIZE);
                    if (size != currentFontSize) SwingUtilities.invokeLater(() -> updateAllFonts(size));
                } catch (Exception ignored) {}
            });

            // First-launch tutorial
            checkFirstLaunch();
        }

        void checkFirstLaunch() {
            java.nio.file.Path marker = java.nio.file.Path.of(System.getProperty("user.home"), ".javaclaw", "tutorial-seen");
            if (!Files.exists(marker)) {
                SwingUtilities.invokeLater(() -> tutorialOverlay.showTutorial());
                try {
                    Files.createDirectories(marker.getParent());
                    Files.writeString(marker, Instant.now().toString());
                } catch (Exception ignored) {}
            }
        }

        void registerShortcuts(JFrame frame) {
            JRootPane root = frame.getRootPane();
            InputMap im = root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
            ActionMap am = root.getActionMap();

            bind(im, am, "F1", "toggleHelp", e -> helpOverlay.toggle());
            bind(im, am, "F2", "newProject", e -> sidebar.createProject());
            bind(im, am, "ctrl N", "newSession", e -> sidebar.createSession());
            bind(im, am, "F3", "runAgent", e -> chatPanel.runAgent());
            bind(im, am, "F4", "pauseAgent", e -> chatPanel.pauseAgent());
            bind(im, am, "F5", "showTools", e -> showToolsDialog());
            bind(im, am, "F6", "focusNav", e -> sidebar.tree.requestFocusInWindow());
            bind(im, am, "F7", "focusChat", e -> chatPanel.inputField.requestFocusInWindow());
            bind(im, am, "ctrl L", "focusChat2", e -> chatPanel.inputField.requestFocusInWindow());
            bind(im, am, "F8", "showTimers", e -> showTimerDialog());
            bind(im, am, "F9", "attachFile", e -> chatPanel.attachFile());
            bind(im, am, "F10", "toggleAgent", e -> agentPane.setVisible(!agentPane.isVisible()));
            bind(im, am, "F11", "toggleSearch", e -> searchPane.setVisible(!searchPane.isVisible()));
            bind(im, am, "F12", "showMemory", e -> showMemoryDialog());
            bind(im, am, "ctrl K", "apiKeys", e -> showApiKeyDialog());
            bind(im, am, "ctrl T", "newThread", e -> sidebar.createThreadInProject());
            bind(im, am, "ctrl H", "showTutorial", e -> tutorialOverlay.showTutorial());
            bind(im, am, "ctrl R", "refreshNav", e -> sidebar.refresh());
            bind(im, am, "ctrl W", "clearChat", e -> chatPanel.clearDisplay());
            bind(im, am, "ctrl EQUALS", "fontUp", e -> updateAllFonts(currentFontSize + 1));
            bind(im, am, "ctrl MINUS", "fontDown", e -> updateAllFonts(currentFontSize - 1));
            bind(im, am, "ctrl 0", "fontReset", e -> updateAllFonts(DEFAULT_FONT_SIZE));
            bind(im, am, "ESCAPE", "dismiss", e -> {
                if (helpOverlay.shown) helpOverlay.toggle();
                else if (tutorialOverlay.isVisible()) tutorialOverlay.hideTutorial();
            });
        }

        void bind(InputMap im, ActionMap am, String key, String name, java.util.function.Consumer<ActionEvent> action) {
            im.put(KeyStroke.getKeyStroke(key), name);
            am.put(name, new AbstractAction() { @Override public void actionPerformed(ActionEvent e) { action.accept(e); } });
        }

        void showToolsDialog() {
            JDialog dlg = new JDialog(SwingUtilities.getWindowAncestor(this), "TOOLS", Dialog.ModalityType.APPLICATION_MODAL);
            dlg.setSize(650, 400);
            dlg.setLocationRelativeTo(this);
            javax.swing.table.DefaultTableModel model = new javax.swing.table.DefaultTableModel(
                    new String[]{"Name", "Description", "Risk"}, 0) {
                @Override public boolean isCellEditable(int r, int c) { return false; }
            };
            JTable table = new JTable(model);
            table.setFont(TERM_FONT);
            CompletableFuture.runAsync(() -> {
                try {
                    JsonNode tools = api.listTools();
                    SwingUtilities.invokeLater(() -> { if (tools.isArray()) for (JsonNode t : tools)
                        model.addRow(new Object[]{t.path("name").asText(), t.path("description").asText(), t.path("riskProfile").asText()}); });
                } catch (Exception ignored) {}
            });
            dlg.setContentPane(new JScrollPane(table));
            dlg.setVisible(true);
        }

        void showTimerDialog() {
            new TimerDialog(SwingUtilities.getWindowAncestor(this), api, chatPanel.currentSessionId).setVisible(true);
        }

        void showApiKeyDialog() {
            new ApiKeyDialog(SwingUtilities.getWindowAncestor(this), api).setVisible(true);
        }

        void showMemoryDialog() {
            JDialog dlg = new JDialog(SwingUtilities.getWindowAncestor(this), "MEMORY BROWSER", Dialog.ModalityType.APPLICATION_MODAL);
            dlg.setSize(750, 500);
            dlg.setLocationRelativeTo(this);

            JPanel p = new JPanel(new BorderLayout(0, 4));
            p.setBackground(Theme.BG);
            p.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

            // Search bar
            JPanel searchBar = new JPanel(new BorderLayout(4, 0));
            searchBar.setBackground(Theme.BG);
            JTextField searchField = new JTextField();
            searchField.setFont(TERM_FONT);
            JButton searchBtn = new JButton("SEARCH");
            searchBtn.setFont(TERM_FONT.deriveFont(11f));
            JComboBox<String> scopeBox = new JComboBox<>(new String[]{"ALL", "GLOBAL", "PROJECT", "SESSION"});
            scopeBox.setFont(TERM_FONT.deriveFont(11f));
            searchBar.add(scopeBox, BorderLayout.WEST);
            searchBar.add(searchField, BorderLayout.CENTER);
            searchBar.add(searchBtn, BorderLayout.EAST);
            p.add(searchBar, BorderLayout.NORTH);

            // Results table
            javax.swing.table.DefaultTableModel model = new javax.swing.table.DefaultTableModel(
                    new String[]{"Key", "Scope", "Content", "Tags", "Updated"}, 0) {
                @Override public boolean isCellEditable(int r, int c) { return false; }
            };
            JTable table = new JTable(model);
            table.setFont(TERM_FONT.deriveFont(11f));
            table.getColumnModel().getColumn(2).setPreferredWidth(300);
            p.add(new JScrollPane(table), BorderLayout.CENTER);

            Runnable doSearch = () -> CompletableFuture.runAsync(() -> {
                try {
                    String scope = scopeBox.getSelectedItem().toString();
                    String query = searchField.getText().trim();
                    JsonNode memories = api.listMemories("ALL".equals(scope) ? null : scope, query.isEmpty() ? null : query);
                    SwingUtilities.invokeLater(() -> {
                        model.setRowCount(0);
                        if (memories.isArray()) for (JsonNode m : memories) {
                            model.addRow(new Object[]{
                                    m.path("key").asText(),
                                    m.path("scope").asText(),
                                    m.path("content").asText().length() > 80 ? m.path("content").asText().substring(0, 80) + "..." : m.path("content").asText(),
                                    m.path("tags").toString(),
                                    m.path("updatedAt").asText()
                            });
                        }
                    });
                } catch (Exception ignored) {}
            });

            searchBtn.addActionListener(e -> doSearch.run());
            searchField.addActionListener(e -> doSearch.run());
            doSearch.run(); // Initial load

            dlg.setContentPane(p);
            dlg.getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke("ESCAPE"), "close");
            dlg.getRootPane().getActionMap().put("close", new AbstractAction() { @Override public void actionPerformed(ActionEvent e) { dlg.dispose(); } });
            dlg.setVisible(true);
        }
    }

    // -----------------------------------------------------------------------
    // Project Navigator Panel (left sidebar — JTree)
    // -----------------------------------------------------------------------
    enum NavNodeType { ROOT_PROJECTS, ROOT_SESSIONS, PROJECT, THREADS_FOLDER, THREAD,
        IDEAS_FOLDER, TICKETS_FOLDER, DESIGNS_FOLDER, SCORECARD_NODE, PLANS_FOLDER, SESSION }

    record NavNode(NavNodeType type, String id, String label, String projectId) {
        @Override public String toString() { return label; }
    }

    static class ProjectNavigatorPanel extends JPanel {
        final ApiClient api;
        final ChatPanel chatPanel;
        final EventSocket eventSocket;
        final javax.swing.tree.DefaultMutableTreeNode rootNode;
        final javax.swing.tree.DefaultTreeModel treeModel;
        final JTree tree;
        String selectedProjectId;

        ProjectNavigatorPanel(ApiClient api, ChatPanel chatPanel, EventSocket eventSocket) {
            this.api = api; this.chatPanel = chatPanel; this.eventSocket = eventSocket;
            setPreferredSize(new Dimension(220, 0));
            setLayout(new BorderLayout(0, 2));
            setBackground(Theme.BG);
            setBorder(BorderFactory.createMatteBorder(0, 0, 0, 1, Theme.BORDER));

            JLabel title = new JLabel(" NAVIGATOR");
            title.setFont(TERM_FONT.deriveFont(Font.BOLD, currentFontSize - 3f));
            title.setForeground(Theme.FG);
            title.setBackground(Theme.HDR_BG);
            title.setOpaque(true);
            title.setPreferredSize(new Dimension(0, 20));
            add(title, BorderLayout.NORTH);

            rootNode = new javax.swing.tree.DefaultMutableTreeNode("ROOT");
            treeModel = new javax.swing.tree.DefaultTreeModel(rootNode);
            tree = new JTree(treeModel);
            tree.setRootVisible(false);
            tree.setShowsRootHandles(true);
            tree.setFont(TERM_FONT.deriveFont(currentFontSize - 4f));
            tree.setBackground(Theme.BG);
            tree.setForeground(Theme.FG);
            tree.setCellRenderer(new NavTreeCellRenderer());
            tree.addTreeSelectionListener(e -> onTreeSelection());
            tree.addTreeExpansionListener(new javax.swing.event.TreeExpansionListener() {
                @Override public void treeExpanded(javax.swing.event.TreeExpansionEvent event) {
                    javax.swing.tree.DefaultMutableTreeNode node =
                            (javax.swing.tree.DefaultMutableTreeNode) event.getPath().getLastPathComponent();
                    if (node.getUserObject() instanceof NavNode nav && nav.type() == NavNodeType.PROJECT) {
                        loadThreadsForProject(nav.id());
                    }
                }
                @Override public void treeCollapsed(javax.swing.event.TreeExpansionEvent event) {}
            });
            tree.addMouseListener(new java.awt.event.MouseAdapter() {
                @Override public void mousePressed(java.awt.event.MouseEvent e) { maybeShowPopup(e); }
                @Override public void mouseReleased(java.awt.event.MouseEvent e) { maybeShowPopup(e); }
                private void maybeShowPopup(java.awt.event.MouseEvent e) {
                    if (!e.isPopupTrigger()) return;
                    int row = tree.getClosestRowForLocation(e.getX(), e.getY());
                    if (row < 0) return;
                    tree.setSelectionRow(row);
                    javax.swing.tree.DefaultMutableTreeNode node =
                            (javax.swing.tree.DefaultMutableTreeNode) tree.getLastSelectedPathComponent();
                    if (node == null || !(node.getUserObject() instanceof NavNode nav)) return;
                    showContextMenu(nav, e.getX(), e.getY());
                }
            });
            JScrollPane sp = new JScrollPane(tree);
            sp.setBorder(null);
            add(sp, BorderLayout.CENTER);

            JPanel btnPanel = new JPanel(new GridLayout(1, 2, 2, 0));
            btnPanel.setBackground(Theme.HDR_BG);
            JButton newProjBtn = new JButton("+ PROJECT");
            newProjBtn.setFont(TERM_FONT.deriveFont(currentFontSize - 5f));
            newProjBtn.addActionListener(e -> createProject());
            JButton newSessBtn = new JButton("+ SESSION");
            newSessBtn.setFont(TERM_FONT.deriveFont(currentFontSize - 5f));
            newSessBtn.addActionListener(e -> createSession());
            btnPanel.add(newProjBtn);
            btnPanel.add(newSessBtn);
            add(btnPanel, BorderLayout.SOUTH);

            fontChangeListeners.add(() -> {
                title.setFont(TERM_FONT.deriveFont(Font.BOLD, currentFontSize - 3f));
                tree.setFont(TERM_FONT.deriveFont(currentFontSize - 4f));
                newProjBtn.setFont(TERM_FONT.deriveFont(currentFontSize - 5f));
                newSessBtn.setFont(TERM_FONT.deriveFont(currentFontSize - 5f));
            });
        }

        void onTreeSelection() {
            javax.swing.tree.DefaultMutableTreeNode selected =
                    (javax.swing.tree.DefaultMutableTreeNode) tree.getLastSelectedPathComponent();
            if (selected == null || !(selected.getUserObject() instanceof NavNode node)) return;

            switch (node.type()) {
                case THREAD -> {
                    selectedProjectId = node.projectId();
                    chatPanel.setThreadSession(node.id(), node.projectId(), node.label());
                    eventSocket.subscribe(node.id());
                }
                case SESSION -> {
                    selectedProjectId = null;
                    chatPanel.setSession(node.id(), node.label());
                    eventSocket.subscribe(node.id());
                }
                case PROJECT -> selectedProjectId = node.id();
                case IDEAS_FOLDER -> showIdeasDialog(node.projectId());
                case TICKETS_FOLDER -> showTicketsDialog(node.projectId());
                case DESIGNS_FOLDER -> showDesignsDialog(node.projectId());
                case SCORECARD_NODE -> showScorecardDialog(node.projectId());
                case PLANS_FOLDER -> showPlansDialog(node.projectId());
                default -> {}
            }
        }

        void showContextMenu(NavNode nav, int x, int y) {
            JPopupMenu popup = new JPopupMenu();
            popup.setBackground(Theme.HDR_BG);
            switch (nav.type()) {
                case PROJECT -> {
                    JMenuItem addThread = new JMenuItem("New Thread");
                    addThread.addActionListener(e -> createThreadInProject(nav.id()));
                    popup.add(addThread);
                    JMenuItem addResource = new JMenuItem("Add Resource...");
                    addResource.addActionListener(e -> showAddResourceDialog(nav.id()));
                    popup.add(addResource);
                    JMenuItem viewResources = new JMenuItem("View Resources");
                    viewResources.addActionListener(e -> showResourcesDialog(nav.id()));
                    popup.add(viewResources);
                    popup.addSeparator();
                    JMenuItem del = new JMenuItem("Delete Project");
                    del.setForeground(Theme.RED);
                    del.addActionListener(e -> {
                        int confirm = JOptionPane.showConfirmDialog(this,
                                "Delete project \"" + nav.label() + "\"?\n\nThis will permanently remove the project and all its threads, tickets, ideas, and designs.",
                                "Confirm Delete", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
                        if (confirm == JOptionPane.YES_OPTION) {
                            CompletableFuture.runAsync(() -> {
                                try { api.delete("/api/projects/" + nav.id()); SwingUtilities.invokeLater(this::refresh); }
                                catch (Exception ex) { SwingUtilities.invokeLater(() -> chatPanel.appendError("Delete failed: " + ex.getMessage())); }
                            });
                        }
                    });
                    popup.add(del);
                }
                case THREAD -> {
                    JMenuItem open = new JMenuItem("Open Thread");
                    open.addActionListener(e -> {
                        chatPanel.setThreadSession(nav.id(), nav.projectId(), nav.label());
                        eventSocket.subscribe(nav.id());
                    });
                    popup.add(open);
                    popup.addSeparator();
                    JMenuItem del = new JMenuItem("Delete Thread");
                    del.setForeground(Theme.RED);
                    del.addActionListener(e -> {
                        int confirm = JOptionPane.showConfirmDialog(this,
                                "Delete thread \"" + nav.label() + "\"?\n\nAll messages in this thread will be lost.",
                                "Confirm Delete", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
                        if (confirm == JOptionPane.YES_OPTION) {
                            CompletableFuture.runAsync(() -> {
                                try {
                                    api.delete("/api/projects/" + nav.projectId() + "/threads/" + nav.id());
                                    SwingUtilities.invokeLater(this::refresh);
                                } catch (Exception ex) { SwingUtilities.invokeLater(() -> chatPanel.appendError("Delete failed: " + ex.getMessage())); }
                            });
                        }
                    });
                    popup.add(del);
                }
                case SESSION -> {
                    JMenuItem del = new JMenuItem("Delete Session");
                    del.setForeground(Theme.RED);
                    del.addActionListener(e -> {
                        int confirm = JOptionPane.showConfirmDialog(this,
                                "Delete standalone session?", "Confirm Delete",
                                JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
                        if (confirm == JOptionPane.YES_OPTION) {
                            CompletableFuture.runAsync(() -> {
                                try { api.delete("/api/sessions/" + nav.id()); SwingUtilities.invokeLater(this::refresh); }
                                catch (Exception ex) { SwingUtilities.invokeLater(() -> chatPanel.appendError("Delete failed: " + ex.getMessage())); }
                            });
                        }
                    });
                    popup.add(del);
                }
                default -> { return; }
            }
            popup.show(tree, x, y);
        }

        void showAddResourceDialog(String projectId) {
            JPanel panel = new JPanel(new GridLayout(4, 2, 4, 4));
            panel.setBackground(Theme.BG);
            JTextField nameField = new JTextField();
            JTextField emailField = new JTextField();
            JComboBox<String> roleBox = new JComboBox<>(new String[]{"ENGINEER", "DESIGNER", "PM", "QA"});
            JTextField skillsField = new JTextField();
            panel.add(new JLabel("Name:")); panel.add(nameField);
            panel.add(new JLabel("Email:")); panel.add(emailField);
            panel.add(new JLabel("Role:")); panel.add(roleBox);
            panel.add(new JLabel("Skills (comma-sep):")); panel.add(skillsField);
            int result = JOptionPane.showConfirmDialog(this, panel, "Add Resource", JOptionPane.OK_CANCEL_OPTION);
            if (result != JOptionPane.OK_OPTION || nameField.getText().isBlank()) return;
            CompletableFuture.runAsync(() -> {
                try {
                    var skills = java.util.Arrays.stream(skillsField.getText().split(","))
                            .map(String::trim).filter(s -> !s.isEmpty()).toList();
                    ObjectNode body = mapper.createObjectNode();
                    body.put("name", nameField.getText());
                    body.put("email", emailField.getText());
                    body.put("role", (String) roleBox.getSelectedItem());
                    body.put("projectId", projectId);
                    var skillsArr = body.putArray("skills");
                    skills.forEach(skillsArr::add);
                    api.post("/api/resources", body);
                    SwingUtilities.invokeLater(() -> chatPanel.appendSystem("Resource added: " + nameField.getText()));
                } catch (Exception ex) {
                    SwingUtilities.invokeLater(() -> chatPanel.appendError("Add resource failed: " + ex.getMessage()));
                }
            });
        }

        void showResourcesDialog(String projectId) {
            CompletableFuture.runAsync(() -> {
                try {
                    JsonNode resources = api.get("/api/resources?projectId=" + projectId);
                    SwingUtilities.invokeLater(() -> {
                        String[] cols = {"Name", "Role", "Email", "Skills"};
                        java.util.List<String[]> rows = new java.util.ArrayList<>();
                        if (resources.isArray()) {
                            for (JsonNode r : resources) {
                                rows.add(new String[]{
                                    r.path("name").asText(), r.path("role").asText(),
                                    r.path("email").asText(""), r.path("skills").toString()
                                });
                            }
                        }
                        JTable table = new JTable(rows.toArray(new String[0][]), cols);
                        table.setFont(TERM_FONT.deriveFont(11f));
                        JDialog dlg = new JDialog((Frame) SwingUtilities.getWindowAncestor(this), "Resources", true);
                        dlg.setSize(500, 300);
                        dlg.add(new JScrollPane(table));
                        dlg.setLocationRelativeTo(this);
                        dlg.setVisible(true);
                    });
                } catch (Exception ex) {
                    SwingUtilities.invokeLater(() -> chatPanel.appendError("Load resources failed: " + ex.getMessage()));
                }
            });
        }

        void refresh() {
            CompletableFuture.runAsync(() -> {
                try {
                    JsonNode projects = api.listProjects();
                    JsonNode sessions = api.listSessions();
                    int projCount = projects.isArray() ? projects.size() : 0;
                    int sessCount = sessions.isArray() ? sessions.size() : 0;
                    System.out.println("[NAV] refresh: " + projCount + " projects, " + sessCount + " sessions");
                    SwingUtilities.invokeLater(() -> buildTree(projects, sessions));
                } catch (Exception ex) {
                    System.err.println("[NAV] refresh failed: " + ex.getMessage());
                    SwingUtilities.invokeLater(() -> chatPanel.appendError("Load failed: " + ex.getMessage()));
                }
            });
        }

        private void buildTree(JsonNode projects, JsonNode sessions) {
            rootNode.removeAllChildren();

            // PROJECTS section
            javax.swing.tree.DefaultMutableTreeNode projRoot = new javax.swing.tree.DefaultMutableTreeNode(
                    new NavNode(NavNodeType.ROOT_PROJECTS, null, "PROJECTS", null));
            projRoot.setAllowsChildren(true);
            if (projects.isArray()) {
                for (JsonNode p : projects) {
                    String pid = p.path("projectId").asText();
                    String name = p.path("name").asText();
                    String status = p.path("status").asText("ACTIVE");
                    javax.swing.tree.DefaultMutableTreeNode projNode = new javax.swing.tree.DefaultMutableTreeNode(
                            new NavNode(NavNodeType.PROJECT, pid, name + "  " + status, null));

                    projNode.add(new javax.swing.tree.DefaultMutableTreeNode(
                            new NavNode(NavNodeType.THREADS_FOLDER, null, "Threads", pid)));
                    projNode.add(new javax.swing.tree.DefaultMutableTreeNode(
                            new NavNode(NavNodeType.IDEAS_FOLDER, null, "Ideas", pid)));
                    projNode.add(new javax.swing.tree.DefaultMutableTreeNode(
                            new NavNode(NavNodeType.TICKETS_FOLDER, null, "Tickets", pid)));
                    projNode.add(new javax.swing.tree.DefaultMutableTreeNode(
                            new NavNode(NavNodeType.DESIGNS_FOLDER, null, "Designs", pid)));
                    projNode.add(new javax.swing.tree.DefaultMutableTreeNode(
                            new NavNode(NavNodeType.SCORECARD_NODE, null, "Scorecard", pid)));
                    projNode.add(new javax.swing.tree.DefaultMutableTreeNode(
                            new NavNode(NavNodeType.PLANS_FOLDER, null, "Plans", pid)));
                    projRoot.add(projNode);
                }
            }
            rootNode.add(projRoot);

            // STANDALONE SESSIONS section
            javax.swing.tree.DefaultMutableTreeNode sessRoot = new javax.swing.tree.DefaultMutableTreeNode(
                    new NavNode(NavNodeType.ROOT_SESSIONS, null, "STANDALONE SESSIONS", null));
            sessRoot.setAllowsChildren(true);
            if (sessions.isArray()) {
                for (JsonNode s : sessions) {
                    String sid = s.path("sessionId").asText();
                    String status = s.path("status").asText();
                    String shortId = sid.length() > 8 ? sid.substring(0, 8) : sid;
                    sessRoot.add(new javax.swing.tree.DefaultMutableTreeNode(
                            new NavNode(NavNodeType.SESSION, sid, shortId + " " + status, null)));
                }
            }
            rootNode.add(sessRoot);

            treeModel.setAsksAllowsChildren(true);
            treeModel.reload();
            // Expand root sections using paths for reliability
            for (int i = 0; i < rootNode.getChildCount(); i++) {
                javax.swing.tree.TreePath path = new javax.swing.tree.TreePath(
                        ((javax.swing.tree.DefaultMutableTreeNode) rootNode.getChildAt(i)).getPath());
                tree.expandPath(path);
            }
        }

        void loadThreadsForProject(String projectId) {
            CompletableFuture.runAsync(() -> {
                try {
                    JsonNode threads = api.listThreads(projectId);
                    SwingUtilities.invokeLater(() -> {
                        // Find the threads folder node for this project
                        javax.swing.tree.DefaultMutableTreeNode threadsFolder = findNode(NavNodeType.THREADS_FOLDER, projectId);
                        if (threadsFolder == null) return;
                        threadsFolder.removeAllChildren();
                        if (threads.isArray()) {
                            for (JsonNode t : threads) {
                                String tid = t.path("threadId").asText();
                                String tTitle = t.path("title").asText("Thread");
                                String tStatus = t.path("status").asText("IDLE");
                                threadsFolder.add(new javax.swing.tree.DefaultMutableTreeNode(
                                        new NavNode(NavNodeType.THREAD, tid, tTitle + " " + tStatus, projectId)));
                            }
                        }
                        treeModel.reload(threadsFolder);
                    });
                } catch (Exception ignored) {}
            });
        }

        javax.swing.tree.DefaultMutableTreeNode findNode(NavNodeType type, String projectId) {
            java.util.Enumeration<?> en = rootNode.depthFirstEnumeration();
            while (en.hasMoreElements()) {
                javax.swing.tree.DefaultMutableTreeNode n = (javax.swing.tree.DefaultMutableTreeNode) en.nextElement();
                if (n.getUserObject() instanceof NavNode nav && nav.type() == type && Objects.equals(nav.projectId(), projectId))
                    return n;
            }
            return null;
        }

        void createProject() {
            String name = JOptionPane.showInputDialog(this, "Project name:", "NEW PROJECT", JOptionPane.PLAIN_MESSAGE);
            if (name == null || name.isBlank()) return;
            String desc = JOptionPane.showInputDialog(this, "Description (optional):", "NEW PROJECT", JOptionPane.PLAIN_MESSAGE);
            CompletableFuture.runAsync(() -> {
                try {
                    JsonNode created = api.createProject(name.trim(), desc);
                    String pid = created.path("projectId").asText();
                    refreshAndSelect(NavNodeType.PROJECT, pid, null);
                } catch (Exception ex) {
                    SwingUtilities.invokeLater(() -> chatPanel.appendError("Create project failed: " + ex.getMessage()));
                }
            });
        }

        void createSession() {
            CompletableFuture.runAsync(() -> {
                try {
                    JsonNode created = api.createSession();
                    String sid = created.path("sessionId").asText();
                    refreshAndSelect(NavNodeType.SESSION, sid, null);
                } catch (Exception ex) {
                    SwingUtilities.invokeLater(() -> chatPanel.appendError("Create session failed: " + ex.getMessage()));
                }
            });
        }

        void createThreadInProject(String projectId) {
            selectedProjectId = projectId;
            createThreadInProject();
        }

        void createThreadInProject() {
            if (selectedProjectId == null) {
                chatPanel.appendError("Select a project first");
                return;
            }
            String title = JOptionPane.showInputDialog(this, "Thread title:", "NEW THREAD", JOptionPane.PLAIN_MESSAGE);
            if (title == null || title.isBlank()) return;
            String pid = selectedProjectId;
            CompletableFuture.runAsync(() -> {
                try {
                    JsonNode created = api.createThread(pid, title.trim());
                    String tid = created.path("threadId").asText();
                    loadThreadsForProject(pid);
                    // Delay to allow tree to reload, then select
                    CompletableFuture.delayedExecutor(500, TimeUnit.MILLISECONDS).execute(() ->
                            SwingUtilities.invokeLater(() -> selectNodeById(NavNodeType.THREAD, tid)));
                } catch (Exception ex) {
                    SwingUtilities.invokeLater(() -> chatPanel.appendError("Create thread failed: " + ex.getMessage()));
                }
            });
        }

        void refreshAndSelect(NavNodeType type, String id, String projectId) {
            CompletableFuture.runAsync(() -> {
                try {
                    JsonNode projects = api.listProjects();
                    JsonNode sessions = api.listSessions();
                    System.out.println("[NAV] refreshAndSelect: " + (projects.isArray() ? projects.size() : 0)
                            + " projects, looking for " + type + " " + id);
                    SwingUtilities.invokeLater(() -> {
                        buildTree(projects, sessions);
                        // Auto-select the newly created item after a brief delay
                        CompletableFuture.delayedExecutor(300, TimeUnit.MILLISECONDS).execute(() ->
                                SwingUtilities.invokeLater(() -> selectNodeById(type, id)));
                    });
                } catch (Exception ex) {
                    System.err.println("[NAV] refreshAndSelect failed: " + ex.getMessage());
                    SwingUtilities.invokeLater(() -> chatPanel.appendError("Refresh failed: " + ex.getMessage()));
                }
            });
        }

        void selectNodeById(NavNodeType type, String id) {
            if (id == null || id.isBlank()) return;
            java.util.Enumeration<?> en = rootNode.depthFirstEnumeration();
            while (en.hasMoreElements()) {
                javax.swing.tree.DefaultMutableTreeNode n = (javax.swing.tree.DefaultMutableTreeNode) en.nextElement();
                if (n.getUserObject() instanceof NavNode nav && nav.type() == type && id.equals(nav.id())) {
                    javax.swing.tree.TreePath path = new javax.swing.tree.TreePath(n.getPath());
                    // Expand parent so node is visible
                    javax.swing.tree.TreePath parentPath = path.getParentPath();
                    if (parentPath != null) tree.expandPath(parentPath);
                    tree.setSelectionPath(path);
                    tree.scrollPathToVisible(path);
                    return;
                }
            }
        }

        // --- Artifact dialogs ---
        void showIdeasDialog(String projectId) {
            JDialog dlg = new JDialog(SwingUtilities.getWindowAncestor(this), "IDEAS", Dialog.ModalityType.APPLICATION_MODAL);
            dlg.setSize(700, 400);
            dlg.setLocationRelativeTo(this);
            javax.swing.table.DefaultTableModel model = new javax.swing.table.DefaultTableModel(
                    new String[]{"Title", "Status", "Tags", "Promoted To"}, 0) {
                @Override public boolean isCellEditable(int r, int c) { return false; }
            };
            JTable table = new JTable(model);
            table.setFont(TERM_FONT.deriveFont(11f));
            table.setBackground(Theme.BG);
            table.setForeground(Theme.FG);

            JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT));
            top.setBackground(Theme.HDR_BG);
            JButton addBtn = new JButton("+ NEW IDEA");
            addBtn.setFont(TERM_FONT.deriveFont(10f));
            JButton promoteBtn = new JButton("PROMOTE TO TICKET");
            promoteBtn.setFont(TERM_FONT.deriveFont(10f));
            top.add(addBtn);
            top.add(promoteBtn);

            Runnable loadIdeas = () -> CompletableFuture.runAsync(() -> {
                try {
                    JsonNode ideas = api.listIdeas(projectId);
                    SwingUtilities.invokeLater(() -> {
                        model.setRowCount(0);
                        if (ideas.isArray()) for (JsonNode i : ideas)
                            model.addRow(new Object[]{i.path("title").asText(), i.path("status").asText(),
                                    i.path("tags").toString(), i.path("promotedToTicketId").asText("")});
                    });
                } catch (Exception ignored) {}
            });

            addBtn.addActionListener(e -> {
                String t = JOptionPane.showInputDialog(dlg, "Idea title:");
                if (t == null || t.isBlank()) return;
                String c = JOptionPane.showInputDialog(dlg, "Content (optional):");
                CompletableFuture.runAsync(() -> {
                    try { api.createIdea(projectId, t.trim(), c); loadIdeas.run(); } catch (Exception ignored) {}
                });
            });

            promoteBtn.addActionListener(e -> {
                int row = table.getSelectedRow();
                if (row < 0) return;
                // Need ideaId - store in hidden way. For simplicity, reload and match by title
                CompletableFuture.runAsync(() -> {
                    try {
                        JsonNode ideas = api.listIdeas(projectId);
                        if (ideas.isArray() && row < ideas.size()) {
                            String ideaId = ideas.get(row).path("ideaId").asText();
                            api.promoteIdea(projectId, ideaId);
                            loadIdeas.run();
                        }
                    } catch (Exception ignored) {}
                });
            });

            loadIdeas.run();
            JPanel p = new JPanel(new BorderLayout());
            p.add(top, BorderLayout.NORTH);
            p.add(new JScrollPane(table), BorderLayout.CENTER);
            dlg.setContentPane(p);
            dlg.getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke("ESCAPE"), "close");
            dlg.getRootPane().getActionMap().put("close", new AbstractAction() { @Override public void actionPerformed(ActionEvent e) { dlg.dispose(); } });
            dlg.setVisible(true);
        }

        void showTicketsDialog(String projectId) {
            JDialog dlg = new JDialog(SwingUtilities.getWindowAncestor(this), "TICKETS", Dialog.ModalityType.APPLICATION_MODAL);
            dlg.setSize(750, 420);
            dlg.setLocationRelativeTo(this);
            javax.swing.table.DefaultTableModel model = new javax.swing.table.DefaultTableModel(
                    new String[]{"Title", "Status", "Priority", "Assigned"}, 0) {
                @Override public boolean isCellEditable(int r, int c) { return false; }
            };
            JTable table = new JTable(model);
            table.setFont(TERM_FONT.deriveFont(11f));
            table.setBackground(Theme.BG);
            table.setForeground(Theme.FG);

            JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT));
            top.setBackground(Theme.HDR_BG);
            JButton addBtn = new JButton("+ NEW TICKET");
            addBtn.setFont(TERM_FONT.deriveFont(10f));
            top.add(addBtn);

            Runnable loadTickets = () -> CompletableFuture.runAsync(() -> {
                try {
                    JsonNode tickets = api.listTickets(projectId);
                    SwingUtilities.invokeLater(() -> {
                        model.setRowCount(0);
                        if (tickets.isArray()) for (JsonNode t : tickets)
                            model.addRow(new Object[]{t.path("title").asText(), t.path("status").asText(),
                                    t.path("priority").asText(), t.path("assignedResourceId").asText("")});
                    });
                } catch (Exception ignored) {}
            });

            addBtn.addActionListener(e -> {
                String t = JOptionPane.showInputDialog(dlg, "Ticket title:");
                if (t == null || t.isBlank()) return;
                String d = JOptionPane.showInputDialog(dlg, "Description (optional):");
                Object[] priorities = {"LOW", "MEDIUM", "HIGH", "CRITICAL"};
                String pri = (String) JOptionPane.showInputDialog(dlg, "Priority:", "PRIORITY",
                        JOptionPane.PLAIN_MESSAGE, null, priorities, "MEDIUM");
                if (pri == null) pri = "MEDIUM";
                String fPri = pri;
                CompletableFuture.runAsync(() -> {
                    try { api.createTicket(projectId, t.trim(), d, fPri); loadTickets.run(); } catch (Exception ignored) {}
                });
            });

            loadTickets.run();
            JPanel p = new JPanel(new BorderLayout());
            p.add(top, BorderLayout.NORTH);
            p.add(new JScrollPane(table), BorderLayout.CENTER);
            dlg.setContentPane(p);
            dlg.getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke("ESCAPE"), "close");
            dlg.getRootPane().getActionMap().put("close", new AbstractAction() { @Override public void actionPerformed(ActionEvent e) { dlg.dispose(); } });
            dlg.setVisible(true);
        }

        void showDesignsDialog(String projectId) {
            JDialog dlg = new JDialog(SwingUtilities.getWindowAncestor(this), "DESIGNS", Dialog.ModalityType.APPLICATION_MODAL);
            dlg.setSize(650, 400);
            dlg.setLocationRelativeTo(this);
            javax.swing.table.DefaultTableModel model = new javax.swing.table.DefaultTableModel(
                    new String[]{"Title", "Source", "Tags", "Version"}, 0) {
                @Override public boolean isCellEditable(int r, int c) { return false; }
            };
            JTable table = new JTable(model);
            table.setFont(TERM_FONT.deriveFont(11f));
            table.setBackground(Theme.BG);
            table.setForeground(Theme.FG);

            JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT));
            top.setBackground(Theme.HDR_BG);
            JButton addBtn = new JButton("+ NEW DESIGN");
            addBtn.setFont(TERM_FONT.deriveFont(10f));
            top.add(addBtn);

            Runnable loadDesigns = () -> CompletableFuture.runAsync(() -> {
                try {
                    JsonNode designs = api.listDesigns(projectId);
                    SwingUtilities.invokeLater(() -> {
                        model.setRowCount(0);
                        if (designs.isArray()) for (JsonNode d : designs)
                            model.addRow(new Object[]{d.path("title").asText(), d.path("source").asText(),
                                    d.path("tags").toString(), d.path("version").asInt(1)});
                    });
                } catch (Exception ignored) {}
            });

            addBtn.addActionListener(e -> {
                String t = JOptionPane.showInputDialog(dlg, "Design title:");
                if (t == null || t.isBlank()) return;
                String src = JOptionPane.showInputDialog(dlg, "Source (e.g. figma, manual):");
                CompletableFuture.runAsync(() -> {
                    try { api.createDesign(projectId, t.trim(), src); loadDesigns.run(); } catch (Exception ignored) {}
                });
            });

            loadDesigns.run();
            JPanel p = new JPanel(new BorderLayout());
            p.add(top, BorderLayout.NORTH);
            p.add(new JScrollPane(table), BorderLayout.CENTER);
            dlg.setContentPane(p);
            dlg.getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke("ESCAPE"), "close");
            dlg.getRootPane().getActionMap().put("close", new AbstractAction() { @Override public void actionPerformed(ActionEvent e) { dlg.dispose(); } });
            dlg.setVisible(true);
        }

        void showScorecardDialog(String projectId) {
            JDialog dlg = new JDialog(SwingUtilities.getWindowAncestor(this), "SCORECARD", Dialog.ModalityType.APPLICATION_MODAL);
            dlg.setSize(500, 350);
            dlg.setLocationRelativeTo(this);
            JPanel p = new JPanel(new BorderLayout(8, 8));
            p.setBackground(Theme.BG);
            p.setBorder(BorderFactory.createEmptyBorder(12, 16, 12, 16));

            JTextPane display = new JTextPane();
            display.setEditable(false);
            display.setFont(TERM_FONT);
            display.setBackground(Theme.BG);
            display.setForeground(Theme.FG);
            p.add(new JScrollPane(display), BorderLayout.CENTER);

            CompletableFuture.runAsync(() -> {
                try {
                    JsonNode sc = api.getScorecard(projectId);
                    SwingUtilities.invokeLater(() -> {
                        StringBuilder sb = new StringBuilder();
                        sb.append("HEALTH: ").append(sc.path("health").asText("UNKNOWN")).append("\n\n");
                        sb.append("METRICS:\n");
                        JsonNode metrics = sc.path("metrics");
                        if (metrics.isObject()) {
                            metrics.fields().forEachRemaining(f ->
                                    sb.append("  ").append(f.getKey()).append(": ").append(f.getValue().asText()).append("\n"));
                        }
                        sb.append("\nLast updated: ").append(sc.path("updatedAt").asText("never"));
                        display.setText(sb.toString());
                    });
                } catch (Exception ex) {
                    SwingUtilities.invokeLater(() -> display.setText("No scorecard yet.\n\nCreate one via the API."));
                }
            });

            dlg.setContentPane(p);
            dlg.getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke("ESCAPE"), "close");
            dlg.getRootPane().getActionMap().put("close", new AbstractAction() { @Override public void actionPerformed(ActionEvent e) { dlg.dispose(); } });
            dlg.setVisible(true);
        }

        void showPlansDialog(String projectId) {
            JDialog dlg = new JDialog(SwingUtilities.getWindowAncestor(this), "PLANS", Dialog.ModalityType.APPLICATION_MODAL);
            dlg.setSize(650, 400);
            dlg.setLocationRelativeTo(this);
            javax.swing.table.DefaultTableModel model = new javax.swing.table.DefaultTableModel(
                    new String[]{"Title", "Milestones", "Tickets", "Updated"}, 0) {
                @Override public boolean isCellEditable(int r, int c) { return false; }
            };
            JTable table = new JTable(model);
            table.setFont(TERM_FONT.deriveFont(11f));
            table.setBackground(Theme.BG);
            table.setForeground(Theme.FG);

            CompletableFuture.runAsync(() -> {
                try {
                    JsonNode plans = api.listPlans(projectId);
                    SwingUtilities.invokeLater(() -> {
                        model.setRowCount(0);
                        if (plans.isArray()) for (JsonNode pl : plans)
                            model.addRow(new Object[]{pl.path("title").asText(),
                                    pl.path("milestones").size(), pl.path("ticketIds").size(),
                                    pl.path("updatedAt").asText("")});
                    });
                } catch (Exception ignored) {}
            });

            dlg.setContentPane(new JScrollPane(table));
            dlg.getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke("ESCAPE"), "close");
            dlg.getRootPane().getActionMap().put("close", new AbstractAction() { @Override public void actionPerformed(ActionEvent e) { dlg.dispose(); } });
            dlg.setVisible(true);
        }
    }

    static class NavTreeCellRenderer extends javax.swing.tree.DefaultTreeCellRenderer {
        @Override public Component getTreeCellRendererComponent(JTree tree, Object value, boolean sel,
                boolean expanded, boolean leaf, int row, boolean hasFocus) {
            super.getTreeCellRendererComponent(tree, value, sel, expanded, leaf, row, hasFocus);
            setFont(TERM_FONT.deriveFont(11f));
            setBackgroundNonSelectionColor(Theme.BG);
            setBackgroundSelectionColor(Theme.SEL_BG);
            setTextNonSelectionColor(Theme.FG);
            setTextSelectionColor(Theme.BLUE);
            setBorderSelectionColor(Theme.BORDER);
            setIcon(null);

            if (((javax.swing.tree.DefaultMutableTreeNode) value).getUserObject() instanceof NavNode node) {
                switch (node.type()) {
                    case ROOT_PROJECTS, ROOT_SESSIONS -> {
                        setForeground(sel ? Theme.BLUE : Theme.AMBER);
                        setFont(TERM_FONT.deriveFont(Font.BOLD, 11f));
                    }
                    case PROJECT -> setForeground(sel ? Theme.BLUE : Theme.FG);
                    case THREAD -> {
                        String lbl = node.label();
                        if (lbl.contains("RUNNING")) setForeground(Theme.FG);
                        else if (lbl.contains("PAUSED")) setForeground(Theme.AMBER);
                        else if (lbl.contains("FAILED")) setForeground(Theme.RED);
                        else setForeground(sel ? Theme.BLUE : Theme.WHITE);
                    }
                    case SESSION -> {
                        String lbl = node.label();
                        if (lbl.contains("RUNNING")) setForeground(Theme.FG);
                        else if (lbl.contains("COMPLETED")) setForeground(Theme.DIM);
                        else if (lbl.contains("FAILED")) setForeground(Theme.RED);
                        else if (lbl.contains("PAUSED")) setForeground(Theme.AMBER);
                        else setForeground(sel ? Theme.BLUE : Theme.WHITE);
                    }
                    case THREADS_FOLDER, IDEAS_FOLDER, TICKETS_FOLDER, DESIGNS_FOLDER,
                            SCORECARD_NODE, PLANS_FOLDER -> setForeground(sel ? Theme.BLUE : Theme.CYAN);
                }
            }
            return this;
        }
    }

    // -----------------------------------------------------------------------
    // Chat Panel (center)
    // -----------------------------------------------------------------------
    static class ChatPanel extends JPanel {
        final ApiClient api;
        final JTextPane chatPane;
        final StyledDocument doc;
        final JTextField inputField;
        final JLabel attachLabel;
        String currentSessionId;
        String currentProjectId; // non-null when using a project thread
        boolean receivingTokens = false;
        final List<AttachedFile> attachedFiles = new ArrayList<>();
        BufferedImage pendingImage;
        final InputHistory history = new InputHistory();
        EventSocket eventSocket; // set from MainPanel to enable auto-subscribe
        AgentPane agentPane; // set from MainPanel to show immediate feedback
        ProjectNavigatorPanel sidebar; // set from MainPanel for refresh after auto-create

        final SimpleAttributeSet userStyle = new SimpleAttributeSet();
        final SimpleAttributeSet assistantStyle = new SimpleAttributeSet();
        final SimpleAttributeSet systemStyle = new SimpleAttributeSet();
        final SimpleAttributeSet errorStyle = new SimpleAttributeSet();
        final SimpleAttributeSet labelStyle = new SimpleAttributeSet();

        ChatPanel(ApiClient api) {
            this.api = api;
            setLayout(new BorderLayout(0, 0));
            setBackground(Theme.BG);

            StyleConstants.setForeground(userStyle, Theme.FG);
            StyleConstants.setForeground(assistantStyle, Theme.CYAN);
            StyleConstants.setForeground(systemStyle, Theme.DIM);
            StyleConstants.setForeground(errorStyle, Theme.RED);
            StyleConstants.setForeground(labelStyle, Theme.AMBER);
            StyleConstants.setBold(labelStyle, true);

            chatPane = new JTextPane();
            chatPane.setEditable(false);
            chatPane.setFont(TERM_FONT);
            chatPane.setBackground(Theme.BG);
            doc = chatPane.getStyledDocument();
            add(new JScrollPane(chatPane), BorderLayout.CENTER);

            // Input area
            JPanel inputArea = new JPanel(new BorderLayout(2, 0));
            inputArea.setBackground(Theme.BG);
            inputArea.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, Theme.BORDER));

            attachLabel = new JLabel("");
            attachLabel.setFont(TERM_FONT.deriveFont(10f));
            attachLabel.setForeground(Theme.AMBER);
            inputArea.add(attachLabel, BorderLayout.NORTH);

            JLabel prompt = new JLabel(" > ");
            prompt.setFont(TERM_FONT);
            prompt.setForeground(Theme.FG);
            inputArea.add(prompt, BorderLayout.WEST);

            inputField = new JTextField();
            inputField.setFont(TERM_FONT);
            inputField.setBackground(Theme.BG);
            inputField.setForeground(Theme.FG);
            inputField.setCaretColor(Theme.FG);
            inputField.setBorder(null);

            // Enter and Ctrl+Enter both send + auto-run
            inputField.addActionListener(e -> sendMessage());
            inputField.getInputMap().put(KeyStroke.getKeyStroke("ctrl ENTER"), "sendAndRun");
            inputField.getActionMap().put("sendAndRun", new AbstractAction() {
                @Override public void actionPerformed(ActionEvent e) { sendMessage(); }
            });

            // Up/Down for input history
            inputField.getInputMap().put(KeyStroke.getKeyStroke("UP"), "histPrev");
            inputField.getActionMap().put("histPrev", new AbstractAction() {
                @Override public void actionPerformed(ActionEvent e) { String p = history.previous(); if (p != null) inputField.setText(p); }
            });
            inputField.getInputMap().put(KeyStroke.getKeyStroke("DOWN"), "histNext");
            inputField.getActionMap().put("histNext", new AbstractAction() {
                @Override public void actionPerformed(ActionEvent e) { String n = history.next(); inputField.setText(n != null ? n : ""); }
            });

            // Ctrl+V for image paste
            inputField.getInputMap().put(KeyStroke.getKeyStroke("ctrl V"), "smartPaste");
            inputField.getActionMap().put("smartPaste", new AbstractAction() {
                @Override public void actionPerformed(ActionEvent e) {
                    try {
                        Clipboard cb = Toolkit.getDefaultToolkit().getSystemClipboard();
                        if (cb.isDataFlavorAvailable(DataFlavor.imageFlavor)) {
                            pendingImage = (BufferedImage) cb.getData(DataFlavor.imageFlavor);
                            updateAttachLabel();
                        } else if (cb.isDataFlavorAvailable(DataFlavor.stringFlavor)) {
                            String text = (String) cb.getData(DataFlavor.stringFlavor);
                            inputField.replaceSelection(text);
                        }
                    } catch (Exception ignored) {}
                }
            });

            inputArea.add(inputField, BorderLayout.CENTER);

            JButton sendBtn = new JButton("SEND");
            sendBtn.setFont(TERM_FONT.deriveFont(Font.BOLD, 11f));
            sendBtn.addActionListener(e -> sendMessage());
            inputArea.add(sendBtn, BorderLayout.EAST);

            add(inputArea, BorderLayout.SOUTH);

            // Font change listener
            fontChangeListeners.add(() -> {
                chatPane.setFont(TERM_FONT);
                inputField.setFont(TERM_FONT);
                sendBtn.setFont(TERM_FONT.deriveFont(Font.BOLD, 11f));
                prompt.setFont(TERM_FONT);
                attachLabel.setFont(TERM_FONT.deriveFont(10f));
            });
        }

        void setSession(String sessionId, String displayName) {
            this.currentSessionId = sessionId;
            this.currentProjectId = null;
            receivingTokens = false;
            attachedFiles.clear();
            pendingImage = null;
            updateAttachLabel();
            try { doc.remove(0, doc.getLength()); } catch (BadLocationException ignored) {}
            appendSystem("Session: " + sessionId);
            inputField.setEnabled(true);
            loadMessageHistory(sessionId);
        }

        void setThreadSession(String threadId, String projectId, String displayName) {
            this.currentSessionId = threadId; // threadId is used as sessionId
            this.currentProjectId = projectId;
            receivingTokens = false;
            attachedFiles.clear();
            pendingImage = null;
            updateAttachLabel();
            try { doc.remove(0, doc.getLength()); } catch (BadLocationException ignored) {}
            appendSystem("Thread: " + displayName + " [project:" + projectId.substring(0, Math.min(8, projectId.length())) + "]");
            inputField.setEnabled(true);
            loadMessageHistory(threadId);
        }

        /** Load past messages with metadata when switching sessions */
        void loadMessageHistory(String sessionId) {
            CompletableFuture.runAsync(() -> {
                try {
                    JsonNode msgs = api.get("/api/sessions/" + sessionId + "/messages");
                    if (!msgs.isArray() || msgs.isEmpty()) return;
                    SwingUtilities.invokeLater(() -> {
                        for (JsonNode m : msgs) {
                            String role = m.path("role").asText();
                            String content = m.path("content").asText();
                            if ("user".equals(role)) {
                                appendLabel("You");
                                appendText(content + "\n", userStyle);
                            } else if ("assistant".equals(role)) {
                                String agent = m.path("agentId").asText(null);
                                String label = agent != null ? "Agent [" + agent + "]" : "Agent";
                                appendLabel(label);
                                appendText(content + "\n", assistantStyle);
                                // Show metadata line
                                String apiProv = m.path("apiProvider").asText(null);
                                long dur = m.path("durationMs").asLong(0);
                                boolean mock = m.path("mocked").asBoolean(false);
                                if (apiProv != null) showAgentMeta(agent != null ? agent : "unknown", apiProv, dur, mock);
                            }
                        }
                    });
                } catch (Exception ignored) {}
            });
        }

        void sendMessage() {
            String text = inputField.getText().trim();
            if (text.isEmpty() && attachedFiles.isEmpty() && pendingImage == null) return;
            inputField.setText("");
            history.add(text);

            // Build payload with file attachments
            StringBuilder payload = new StringBuilder();
            for (AttachedFile af : attachedFiles) {
                payload.append("[FILE: ").append(af.path).append("]\n");
                payload.append(af.content).append("\n");
                payload.append("[END FILE]\n\n");
            }
            payload.append(text);
            String finalText = payload.toString();

            appendLabel("You");
            appendText(text + (attachedFiles.isEmpty() ? "" : " [+" + attachedFiles.size() + " files]") + "\n", userStyle);
            if (pendingImage != null) appendText("[image attached]\n", systemStyle);

            boolean hasImage = pendingImage != null;
            String imgBase64 = null;
            if (hasImage) {
                try {
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    ImageIO.write(pendingImage, "png", baos);
                    imgBase64 = Base64.getEncoder().encodeToString(baos.toByteArray());
                } catch (Exception ignored) {}
            }

            attachedFiles.clear();
            pendingImage = null;
            updateAttachLabel();

            String fImgBase64 = imgBase64;

            // Show immediate feedback in agent pane
            if (agentPane != null) agentPane.onAgentEvent("Processing message...");

            CompletableFuture.runAsync(() -> {
                try {
                    // Auto-create session if none exists
                    if (currentSessionId == null) {
                        JsonNode created = api.createSession();
                        String newSid = created.path("sessionId").asText();
                        if (newSid.isBlank()) throw new IllegalStateException("Failed to create session");
                        SwingUtilities.invokeLater(() -> {
                            setSession(newSid, newSid.substring(0, Math.min(8, newSid.length())) + " IDLE");
                            if (eventSocket != null) eventSocket.subscribe(newSid);
                            if (sidebar != null) sidebar.refresh();
                        });
                        // Brief wait for UI to update
                        Thread.sleep(200);
                    }

                    // Route to correct API based on whether this is a thread or standalone session
                    if (currentProjectId != null) {
                        api.sendThreadMessage(currentProjectId, currentSessionId, finalText);
                    } else {
                        if (fImgBase64 != null) api.sendMultimodal(currentSessionId, finalText, fImgBase64);
                        else api.sendMessage(currentSessionId, finalText);
                    }

                    // Auto-run agent after sending
                    Thread.sleep(200); // Brief delay to ensure message persists
                    doRunAgent();
                } catch (Exception ex) {
                    SwingUtilities.invokeLater(() -> appendError("Send failed: " + ex.getMessage()));
                }
            });
        }

        private void doRunAgent() {
            if (currentSessionId == null) return;
            SwingUtilities.invokeLater(() -> appendSystem("Starting agent..."));
            try {
                if (currentProjectId != null) {
                    api.runThread(currentProjectId, currentSessionId);
                } else {
                    api.runSession(currentSessionId);
                }
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> appendError("Run failed: " + ex.getMessage()));
            }
        }

        void runAgent() {
            if (currentSessionId == null) return;
            appendSystem("Starting agent...");
            CompletableFuture.runAsync(() -> {
                try {
                    if (currentProjectId != null) api.runThread(currentProjectId, currentSessionId);
                    else api.runSession(currentSessionId);
                } catch (Exception ex) {
                    SwingUtilities.invokeLater(() -> appendError("Run failed: " + ex.getMessage()));
                }
            });
        }

        void pauseAgent() {
            if (currentSessionId == null) return;
            CompletableFuture.runAsync(() -> {
                try {
                    if (currentProjectId != null) api.pauseThread(currentProjectId, currentSessionId);
                    else api.pauseSession(currentSessionId);
                    SwingUtilities.invokeLater(() -> appendSystem("Pause requested"));
                } catch (Exception ex) {
                    SwingUtilities.invokeLater(() -> appendError("Pause failed: " + ex.getMessage()));
                }
            });
        }

        void attachFile() {
            JFileChooser fc = new JFileChooser();
            fc.setDialogTitle("ATTACH FILE");
            fc.setMultiSelectionEnabled(true);
            if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
                for (File file : fc.getSelectedFiles()) {
                    try {
                        String content = Files.readString(file.toPath());
                        if (content.length() > 102400) {
                            int choice = JOptionPane.showConfirmDialog(this,
                                    "File " + file.getName() + " is " + (content.length() / 1024) + "KB. Attach?",
                                    "Large File", JOptionPane.YES_NO_OPTION);
                            if (choice != JOptionPane.YES_OPTION) continue;
                        }
                        attachedFiles.add(new AttachedFile(file.getName(), file.getAbsolutePath(), content));
                    } catch (Exception ex) { appendError("Read failed: " + file.getName()); }
                }
                updateAttachLabel();
            }
        }

        void updateAttachLabel() {
            StringBuilder sb = new StringBuilder();
            if (!attachedFiles.isEmpty()) {
                sb.append(" ATT: ");
                for (AttachedFile af : attachedFiles) sb.append(af.name).append(" ");
            }
            if (pendingImage != null) {
                sb.append(" IMG: ").append(pendingImage.getWidth()).append("x").append(pendingImage.getHeight());
            }
            attachLabel.setText(sb.toString());
        }

        void clearDisplay() { try { doc.remove(0, doc.getLength()); } catch (BadLocationException ignored) {} }

        String currentAgentId = null;

        void setCurrentAgent(String agentId) { this.currentAgentId = agentId; }

        void appendToken(String token) {
            if (!receivingTokens) {
                receivingTokens = true;
                String label = currentAgentId != null ? "Agent [" + currentAgentId + "]" : "Agent";
                appendLabel(label);
            }
            appendText(token, assistantStyle);
        }

        void finishAssistantMessage() { if (receivingTokens) { appendText("\n", assistantStyle); receivingTokens = false; } }

        void showAgentMeta(String agentId, String apiProvider, long durationMs, boolean mocked) {
            String tag = mocked ? " MOCK" : " LIVE";
            String meta = String.format("  [%s | api=%s | %dms%s]", agentId, apiProvider, durationMs, tag);
            appendText(meta + "\n", systemStyle);
        }
        void appendSystem(String text) { finishAssistantMessage(); appendText(text + "\n", systemStyle); }
        void appendError(String text) { finishAssistantMessage(); appendText(text + "\n", errorStyle); }
        void appendLabel(String label) { appendText("\n" + label + ": ", labelStyle); }

        private void appendText(String text, AttributeSet style) {
            try { doc.insertString(doc.getLength(), text, style); chatPane.setCaretPosition(doc.getLength()); } catch (BadLocationException ignored) {}
        }
    }

    // -----------------------------------------------------------------------
    // Agent Pane (right panel)
    // -----------------------------------------------------------------------
    static class AgentPane extends JPanel {
        final ApiClient api;
        final JTextPane activityLog;
        final StyledDocument logDoc;
        final JLabel statusLabel;
        final JLabel statsLabel;
        int stepCount = 0, toolCount = 0;

        final SimpleAttributeSet stepStyle = new SimpleAttributeSet();
        final SimpleAttributeSet toolStyle = new SimpleAttributeSet();
        final SimpleAttributeSet eventStyle = new SimpleAttributeSet();
        final SimpleAttributeSet errorAgentStyle = new SimpleAttributeSet();
        final SimpleAttributeSet dimAgentStyle = new SimpleAttributeSet();

        AgentPane(ApiClient api) {
            this.api = api;
            setLayout(new BorderLayout(0, 2));
            setPreferredSize(new Dimension(280, 0));
            setBackground(Theme.BG);
            setBorder(BorderFactory.createMatteBorder(0, 1, 0, 0, Theme.BORDER));

            StyleConstants.setForeground(stepStyle, Theme.AMBER);
            StyleConstants.setBold(stepStyle, true);
            StyleConstants.setForeground(toolStyle, Theme.CYAN);
            StyleConstants.setForeground(eventStyle, Theme.FG);
            StyleConstants.setForeground(errorAgentStyle, Theme.RED);
            StyleConstants.setForeground(dimAgentStyle, Theme.DIM);

            // Header
            JPanel hdr = new JPanel(new BorderLayout());
            hdr.setBackground(Theme.HDR_BG);
            JLabel title = new JLabel(" AGENTS");
            title.setFont(TERM_FONT.deriveFont(Font.BOLD, currentFontSize - 3f));
            title.setForeground(Theme.FG);
            hdr.add(title, BorderLayout.WEST);
            statusLabel = new JLabel("IDLE ");
            statusLabel.setFont(TERM_FONT.deriveFont(currentFontSize - 4f));
            statusLabel.setForeground(Theme.DIM);
            hdr.add(statusLabel, BorderLayout.EAST);
            add(hdr, BorderLayout.NORTH);

            // Activity log
            activityLog = new JTextPane();
            activityLog.setEditable(false);
            activityLog.setFont(TERM_FONT.deriveFont(currentFontSize - 4f));
            activityLog.setBackground(Theme.BG);
            logDoc = activityLog.getStyledDocument();
            add(new JScrollPane(activityLog), BorderLayout.CENTER);

            // Stats
            statsLabel = new JLabel(" Steps: 0 | Tools: 0");
            statsLabel.setFont(TERM_FONT.deriveFont(currentFontSize - 5f));
            statsLabel.setForeground(Theme.DIM);
            statsLabel.setBackground(Theme.HDR_BG);
            statsLabel.setOpaque(true);
            add(statsLabel, BorderLayout.SOUTH);

            fontChangeListeners.add(() -> {
                title.setFont(TERM_FONT.deriveFont(Font.BOLD, currentFontSize - 3f));
                statusLabel.setFont(TERM_FONT.deriveFont(currentFontSize - 4f));
                activityLog.setFont(TERM_FONT.deriveFont(currentFontSize - 4f));
                statsLabel.setFont(TERM_FONT.deriveFont(currentFontSize - 5f));
            });
        }

        void refreshAgents() {
            CompletableFuture.runAsync(() -> {
                try {
                    JsonNode agents = api.listAgents();
                    SwingUtilities.invokeLater(() -> {
                        if (agents.isArray()) for (JsonNode a : agents)
                            appendLog("[" + a.path("agentId").asText() + "] " + a.path("description").asText(), dimAgentStyle);
                    });
                } catch (Exception ignored) {}
            });
        }

        void onStepStarted(String agentId) {
            stepCount++;
            String prefix = agentId != null ? "[" + agentId + "] " : "";
            appendLog(prefix + "STEP " + stepCount + " started", stepStyle);
            updateStats();
        }

        void onStepCompleted(String agentId) {
            String prefix = agentId != null ? "[" + agentId + "] " : "";
            appendLog(prefix + "STEP " + stepCount + " completed", dimAgentStyle);
        }

        void onToolCall(String toolName) { toolCount++; appendLog("Tool: " + toolName, toolStyle); updateStats(); }
        void onStatusChanged(String status) {
            statusLabel.setText(status + " ");
            statusLabel.setForeground(switch (status) {
                case "RUNNING" -> Theme.FG;
                case "PAUSED" -> Theme.AMBER;
                case "FAILED" -> Theme.RED;
                default -> Theme.DIM;
            });
            appendLog("Status: " + status, eventStyle);
        }
        void onAgentEvent(String text) { appendLog(text, eventStyle); }
        void onError(String text) { appendLog("ERROR: " + text, errorAgentStyle); }

        void updateStats() { statsLabel.setText(" Steps: " + stepCount + " | Tools: " + toolCount); }

        void appendLog(String text, AttributeSet style) {
            String ts = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
            try {
                logDoc.insertString(logDoc.getLength(), ts + "  " + text + "\n", style);
                // Rolling buffer: trim if > 500 lines
                String content = logDoc.getText(0, logDoc.getLength());
                int lines = content.split("\n", -1).length;
                if (lines > 500) {
                    int firstNewline = content.indexOf('\n');
                    if (firstNewline >= 0) logDoc.remove(0, firstNewline + 1);
                }
                activityLog.setCaretPosition(logDoc.getLength());
            } catch (BadLocationException ignored) {}
        }
    }

    // -----------------------------------------------------------------------
    // Search Request Pane (agent asks human to search)
    // -----------------------------------------------------------------------
    static class SearchRequestPane extends JPanel {
        final ApiClient api;
        final ChatPanel chatPanel;
        final JLabel queryLabel;
        final JTextArea pasteArea;
        final JButton submitBtn;
        final JButton openBrowserBtn;
        String currentRequestId;
        String currentSearchUrl;

        SearchRequestPane(ApiClient api, ChatPanel chatPanel) {
            this.api = api;
            this.chatPanel = chatPanel;
            setLayout(new BorderLayout(4, 4));
            setBackground(Theme.HDR_BG);
            setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createMatteBorder(1, 0, 0, 0, Theme.BORDER),
                    BorderFactory.createEmptyBorder(6, 8, 6, 8)));
            setPreferredSize(new Dimension(0, 160));

            // Header
            JPanel header = new JPanel(new BorderLayout());
            header.setBackground(Theme.HDR_BG);
            JLabel title = new JLabel("SEARCH REQUEST FROM AGENT");
            title.setFont(TERM_FONT.deriveFont(Font.BOLD, 12f));
            title.setForeground(Theme.AMBER);
            header.add(title, BorderLayout.WEST);

            openBrowserBtn = new JButton("OPEN BROWSER");
            openBrowserBtn.setFont(TERM_FONT.deriveFont(10f));
            openBrowserBtn.addActionListener(e -> openBrowser());
            header.add(openBrowserBtn, BorderLayout.EAST);
            add(header, BorderLayout.NORTH);

            queryLabel = new JLabel("Waiting for agent search request...");
            queryLabel.setFont(TERM_FONT.deriveFont(11f));
            queryLabel.setForeground(Theme.FG);

            // Paste area
            pasteArea = new JTextArea(4, 40);
            pasteArea.setFont(TERM_FONT.deriveFont(11f));
            pasteArea.setBackground(Theme.BG);
            pasteArea.setForeground(Theme.FG);
            pasteArea.setCaretColor(Theme.FG);
            pasteArea.setLineWrap(true);
            pasteArea.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(Theme.BORDER),
                    BorderFactory.createEmptyBorder(4, 4, 4, 4)));

            JPanel centerPanel = new JPanel(new BorderLayout(0, 4));
            centerPanel.setBackground(Theme.HDR_BG);
            centerPanel.add(queryLabel, BorderLayout.NORTH);
            centerPanel.add(new JScrollPane(pasteArea), BorderLayout.CENTER);
            add(centerPanel, BorderLayout.CENTER);

            // Submit button
            submitBtn = new JButton("SUBMIT RESULTS TO AGENT");
            submitBtn.setFont(TERM_FONT.deriveFont(Font.BOLD, 11f));
            submitBtn.addActionListener(e -> submitResults());
            add(submitBtn, BorderLayout.SOUTH);
        }

        void onSearchRequested(String requestId, String query, String searchUrl) {
            this.currentRequestId = requestId;
            this.currentSearchUrl = searchUrl;
            queryLabel.setText("Search: " + query);
            pasteArea.setText("");
            setVisible(true);
            chatPanel.appendSystem("Agent requests search: " + query);

            // Auto-open browser
            openBrowser();
        }

        void openBrowser() {
            if (currentSearchUrl == null) return;
            try {
                if (java.awt.Desktop.isDesktopSupported()) {
                    java.awt.Desktop.getDesktop().browse(URI.create(currentSearchUrl));
                }
            } catch (Exception ex) {
                chatPanel.appendError("Failed to open browser: " + ex.getMessage());
            }
        }

        void submitResults() {
            if (currentRequestId == null || chatPanel.currentSessionId == null) return;
            String content = pasteArea.getText().trim();
            if (content.isEmpty()) {
                JOptionPane.showMessageDialog(this, "Paste search results first (Ctrl+A, Ctrl+C from browser, then Ctrl+V here)",
                        "No Content", JOptionPane.WARNING_MESSAGE);
                return;
            }

            CompletableFuture.runAsync(() -> {
                try {
                    api.submitSearchResponse(chatPanel.currentSessionId, currentRequestId, content);
                    SwingUtilities.invokeLater(() -> {
                        chatPanel.appendSystem("Search results submitted (" + content.length() + " chars)");
                        pasteArea.setText("");
                        currentRequestId = null;
                        setVisible(false);
                    });
                } catch (Exception ex) {
                    SwingUtilities.invokeLater(() -> chatPanel.appendError("Submit failed: " + ex.getMessage()));
                }
            });
        }
    }

    // -----------------------------------------------------------------------
    // Timer Dialog (F8)
    // -----------------------------------------------------------------------
    static class TimerDialog extends JDialog {
        TimerDialog(Window owner, ApiClient api, String sessionId) {
            super(owner, "TIMER / REMINDER", ModalityType.APPLICATION_MODAL);
            setSize(550, 420);
            setLocationRelativeTo(owner);
            JPanel p = new JPanel();
            p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
            p.setBackground(Theme.BG);
            p.setBorder(BorderFactory.createEmptyBorder(12, 16, 12, 16));

            JLabel title = new JLabel("CREATE TIMER");
            title.setFont(TERM_FONT.deriveFont(Font.BOLD, 14f));
            title.setForeground(Theme.FG);
            title.setAlignmentX(LEFT_ALIGNMENT);
            p.add(title);
            p.add(Box.createVerticalStrut(12));

            JTextField msgField = new JTextField(30);
            msgField.setFont(TERM_FONT);
            addRow(p, "Message:", msgField);

            JRadioButton recurring = new JRadioButton("Recurring");
            recurring.setFont(TERM_FONT); recurring.setForeground(Theme.FG); recurring.setBackground(Theme.BG); recurring.setSelected(true);
            JRadioButton oneshot = new JRadioButton("One-shot");
            oneshot.setFont(TERM_FONT); oneshot.setForeground(Theme.FG); oneshot.setBackground(Theme.BG);
            ButtonGroup bg = new ButtonGroup(); bg.add(recurring); bg.add(oneshot);
            JPanel typePanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
            typePanel.setBackground(Theme.BG);
            typePanel.add(recurring); typePanel.add(oneshot);
            typePanel.setAlignmentX(LEFT_ALIGNMENT);
            p.add(typePanel);
            p.add(Box.createVerticalStrut(8));

            JTextField timesField = new JTextField("3", 5);
            timesField.setFont(TERM_FONT);
            addRow(p, "Times/day:", timesField);

            JTextField startField = new JTextField(LocalTime.now().plusHours(1).format(DateTimeFormatter.ofPattern("HH:mm")), 8);
            startField.setFont(TERM_FONT);
            addRow(p, "Start at:", startField);

            p.add(Box.createVerticalStrut(12));

            JButton createBtn = new JButton("CREATE");
            createBtn.setFont(TERM_FONT.deriveFont(Font.BOLD));
            createBtn.setAlignmentX(LEFT_ALIGNMENT);
            createBtn.addActionListener(e -> {
                try {
                    String msg = msgField.getText().trim();
                    if (msg.isEmpty()) return;
                    boolean isRecurring = recurring.isSelected();
                    Long interval = null;
                    String triggerAt = null;
                    if (isRecurring) {
                        int times = Integer.parseInt(timesField.getText().trim());
                        interval = 86400L / times;
                    }
                    // Parse start time to create triggerAt
                    String[] parts = startField.getText().trim().split(":");
                    LocalTime lt = LocalTime.of(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]));
                    triggerAt = Instant.now().atZone(ZoneId.systemDefault())
                            .withHour(lt.getHour()).withMinute(lt.getMinute()).withSecond(0).toInstant().toString();

                    api.createReminder(sessionId, msg, isRecurring, interval, triggerAt);
                    JOptionPane.showMessageDialog(this, "Timer created!", "OK", JOptionPane.INFORMATION_MESSAGE);
                } catch (Exception ex) {
                    JOptionPane.showMessageDialog(this, "Error: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
                }
            });
            p.add(createBtn);

            setContentPane(new JScrollPane(p));

            getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke("ESCAPE"), "close");
            getRootPane().getActionMap().put("close", new AbstractAction() { @Override public void actionPerformed(ActionEvent e) { dispose(); } });
        }

        void addRow(JPanel p, String label, JTextField field) {
            JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 2));
            row.setBackground(Theme.BG);
            row.setAlignmentX(Component.LEFT_ALIGNMENT);
            JLabel l = new JLabel(String.format("%-12s", label));
            l.setFont(TERM_FONT); l.setForeground(Theme.DIM);
            row.add(l); row.add(field);
            p.add(row);
        }
    }

    // -----------------------------------------------------------------------
    // API Key Dialog (Ctrl+K)
    // -----------------------------------------------------------------------
    static class ApiKeyDialog extends JDialog {
        ApiKeyDialog(Window owner, ApiClient api) {
            super(owner, "API CONFIGURATION", ModalityType.APPLICATION_MODAL);
            setSize(520, 280);
            setLocationRelativeTo(owner);
            JPanel p = new JPanel();
            p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
            p.setBackground(Theme.BG);
            p.setBorder(BorderFactory.createEmptyBorder(16, 20, 16, 20));

            JLabel title = new JLabel("API KEYS (in-memory only, not saved to disk)");
            title.setFont(TERM_FONT.deriveFont(Font.BOLD, 13f));
            title.setForeground(Theme.FG);
            title.setAlignmentX(LEFT_ALIGNMENT);
            p.add(title);
            p.add(Box.createVerticalStrut(16));

            JLabel antLabel = new JLabel("Anthropic API Key:");
            antLabel.setFont(TERM_FONT); antLabel.setForeground(Theme.DIM); antLabel.setAlignmentX(LEFT_ALIGNMENT);
            p.add(antLabel);
            JPasswordField antField = new JPasswordField(40);
            antField.setFont(TERM_FONT); antField.setAlignmentX(LEFT_ALIGNMENT);
            p.add(antField);
            p.add(Box.createVerticalStrut(12));

            JLabel oaiLabel = new JLabel("OpenAI API Key:");
            oaiLabel.setFont(TERM_FONT); oaiLabel.setForeground(Theme.DIM); oaiLabel.setAlignmentX(LEFT_ALIGNMENT);
            p.add(oaiLabel);
            JPasswordField oaiField = new JPasswordField(40);
            oaiField.setFont(TERM_FONT); oaiField.setAlignmentX(LEFT_ALIGNMENT);
            p.add(oaiField);
            p.add(Box.createVerticalStrut(16));

            JButton saveBtn = new JButton("SAVE");
            saveBtn.setFont(TERM_FONT.deriveFont(Font.BOLD));
            saveBtn.setAlignmentX(LEFT_ALIGNMENT);
            saveBtn.addActionListener(e -> {
                try {
                    String antKey = new String(antField.getPassword()).trim();
                    String oaiKey = new String(oaiField.getPassword()).trim();
                    api.setApiKeys(antKey.isEmpty() ? null : antKey, oaiKey.isEmpty() ? null : oaiKey);
                    JOptionPane.showMessageDialog(this, "Keys updated (in-memory only)", "OK", JOptionPane.INFORMATION_MESSAGE);
                    dispose();
                } catch (Exception ex) {
                    JOptionPane.showMessageDialog(this, "Error: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
                }
            });
            p.add(saveBtn);

            setContentPane(p);

            getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke("ESCAPE"), "close");
            getRootPane().getActionMap().put("close", new AbstractAction() { @Override public void actionPerformed(ActionEvent e) { dispose(); } });
        }
    }

    // -----------------------------------------------------------------------
    // Tutorial Overlay (Ctrl+H / first launch)
    // -----------------------------------------------------------------------
    static class TutorialOverlay extends JComponent {
        int step = 0;
        static final String[][] STEPS = {
                {"Welcome to JavaClaw",
                        "JavaClaw is a Bloomberg-style AI terminal for\n" +
                        "engineering managers. It combines project management,\n" +
                        "AI-powered chat threads, and tool execution in\n" +
                        "a single interface.\n\n" +
                        "Use Enter/Right to advance, Left to go back,\n" +
                        "or Escape to close this tutorial."},
                {"Projects & Navigation",
                        "The left sidebar is your Project Navigator.\n\n" +
                        "  F2         Create a new project\n" +
                        "  Ctrl+N     Create a standalone session\n" +
                        "  Ctrl+T     New thread in current project\n" +
                        "  F6         Focus the navigator\n\n" +
                        "Each project contains Threads, Ideas, Tickets,\n" +
                        "Designs, Plans, and a Scorecard.\n" +
                        "Click folders to open artifact dialogs."},
                {"Chat & Agent Interaction",
                        "Select a thread or session, or just type and\n" +
                        "press Enter — a session is auto-created if needed.\n\n" +
                        "  Enter       Send message + auto-run agent.\n" +
                        "  F3          Re-run agent on current thread.\n" +
                        "  F4          Pause agent.\n" +
                        "  F9          Attach files to your message.\n" +
                        "  Ctrl+V      Paste images from clipboard.\n\n" +
                        "Agent responses stream in real-time via WebSocket."},
                {"Tools & Agents",
                        "JavaClaw agents can use tools to help you:\n\n" +
                        "  - Read/Write files (WSL + Windows paths)\n" +
                        "  - Run JBang and Python scripts\n" +
                        "  - Search the web (human-in-the-loop)\n" +
                        "  - Read Excel spreadsheets\n" +
                        "  - Manage persistent memory\n\n" +
                        "  F5      View all available tools\n" +
                        "  F10     Toggle the agent activity pane\n" +
                        "  F11     Toggle search request pane"},
                {"Keyboard Shortcuts",
                        "Essential shortcuts for power users:\n\n" +
                        "  F1         Full keyboard reference\n" +
                        "  Ctrl+=     Increase font size\n" +
                        "  Ctrl+-     Decrease font size\n" +
                        "  Ctrl+0     Reset font to 15pt default\n" +
                        "  Ctrl+K     Configure API keys\n" +
                        "  Ctrl+R     Refresh navigator\n" +
                        "  Ctrl+W     Clear chat display\n" +
                        "  F8         Timer/reminder manager\n" +
                        "  F12        Memory browser"},
                {"You're Ready!",
                        "Start by creating a project (F2) and adding\n" +
                        "a thread (Ctrl+T) to begin chatting with the AI.\n\n" +
                        "Or create a standalone session (Ctrl+N) for\n" +
                        "quick, project-free interactions.\n\n" +
                        "Press Ctrl+H anytime to re-open this tutorial.\n\n" +
                        "Happy building!"},
        };

        TutorialOverlay() {
            setOpaque(false);
            setFocusable(true);
            addKeyListener(new KeyAdapter() {
                @Override public void keyPressed(KeyEvent e) {
                    switch (e.getKeyCode()) {
                        case KeyEvent.VK_RIGHT, KeyEvent.VK_ENTER -> { if (step < STEPS.length - 1) { step++; repaint(); } else hideTutorial(); }
                        case KeyEvent.VK_LEFT -> { if (step > 0) { step--; repaint(); } }
                        case KeyEvent.VK_ESCAPE -> hideTutorial();
                    }
                }
            });
            addMouseListener(new MouseAdapter() {
                @Override public void mouseClicked(MouseEvent e) {
                    if (step < STEPS.length - 1) { step++; repaint(); } else hideTutorial();
                }
            });
        }

        public void showTutorial() { step = 0; setVisible(true); requestFocusInWindow(); repaint(); }
        public void hideTutorial() { setVisible(false); }

        @Override protected void paintComponent(Graphics g) {
            if (!isVisible()) return;
            Graphics2D g2 = (Graphics2D) g;
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(new Color(0, 0, 0, 210));
            g2.fillRect(0, 0, getWidth(), getHeight());

            int bw = 600, bh = 420;
            int x = (getWidth() - bw) / 2, y = (getHeight() - bh) / 2;
            g2.setColor(Theme.BG);
            g2.fillRect(x, y, bw, bh);
            g2.setColor(Theme.FG);
            g2.drawRect(x, y, bw, bh);

            // Step indicator
            g2.setFont(TERM_FONT.deriveFont(11f));
            g2.setColor(Theme.DIM);
            String indicator = "Step " + (step + 1) + "/" + STEPS.length;
            g2.drawString(indicator, x + bw - 100, y + 25);

            // Title
            g2.setFont(TERM_FONT.deriveFont(Font.BOLD, 18f));
            g2.setColor(Theme.AMBER);
            g2.drawString(STEPS[step][0], x + 30, y + 45);

            // Body
            g2.setFont(TERM_FONT.deriveFont(13f));
            g2.setColor(Theme.FG);
            int ly = y + 80;
            for (String line : STEPS[step][1].split("\n")) {
                g2.drawString(line, x + 30, ly);
                ly += 22;
            }

            // Navigation hint
            g2.setFont(TERM_FONT.deriveFont(11f));
            g2.setColor(Theme.DIM);
            String nav = step < STEPS.length - 1 ? "Enter/Right: Next  |  Left: Back  |  Esc: Close" : "Enter: Close  |  Esc: Close";
            g2.drawString(nav, x + 30, y + bh - 20);

            // Progress bar
            int pbw = bw - 60;
            g2.setColor(Theme.BORDER);
            g2.fillRect(x + 30, y + bh - 45, pbw, 4);
            g2.setColor(Theme.FG);
            g2.fillRect(x + 30, y + bh - 45, pbw * (step + 1) / STEPS.length, 4);
        }
    }

    // -----------------------------------------------------------------------
    // Input History
    // -----------------------------------------------------------------------
    static class InputHistory {
        private final List<String> entries = new ArrayList<>();
        private int cursor = -1;

        void add(String text) { if (!text.isBlank()) { entries.add(text); cursor = entries.size(); } }
        String previous() { if (cursor > 0) cursor--; return cursor >= 0 && cursor < entries.size() ? entries.get(cursor) : null; }
        String next() { if (cursor < entries.size()) cursor++; return cursor < entries.size() ? entries.get(cursor) : null; }
    }

    record AttachedFile(String name, String path, String content) {}
}
