package io.github.drompincen.javaclawv1.ui.view;

import io.github.drompincen.javaclawv1.persistence.document.EventDocument;
import io.github.drompincen.javaclawv1.persistence.document.MessageDocument;
import io.github.drompincen.javaclawv1.persistence.repository.MessageRepository;
import io.github.drompincen.javaclawv1.persistence.stream.ChangeStreamService;
import io.github.drompincen.javaclawv1.runtime.agent.AgentLoop;
import io.github.drompincen.javaclawv1.runtime.agent.EventService;
import io.github.drompincen.javaclawv1.protocol.event.EventType;
import org.springframework.stereotype.Component;

import javax.swing.*;
import javax.swing.text.*;
import java.awt.*;
import java.time.Instant;
import java.util.UUID;

@Component
public class ThreadView extends JPanel {

    private final MessageRepository messageRepository;
    private final ChangeStreamService changeStreamService;
    private final AgentLoop agentLoop;
    private final EventService eventService;

    private final JTextPane chatPane = new JTextPane();
    private final JTextField inputField = new JTextField();
    private String currentSessionId;

    public ThreadView(MessageRepository messageRepository,
                      ChangeStreamService changeStreamService,
                      AgentLoop agentLoop,
                      EventService eventService) {
        this.messageRepository = messageRepository;
        this.changeStreamService = changeStreamService;
        this.agentLoop = agentLoop;
        this.eventService = eventService;
        buildUI();
    }

    private void buildUI() {
        setLayout(new BorderLayout(0, 8));
        setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        chatPane.setEditable(false);
        JScrollPane scrollPane = new JScrollPane(chatPane);
        add(scrollPane, BorderLayout.CENTER);

        JPanel inputBar = new JPanel(new BorderLayout(8, 0));
        inputField.addActionListener(e -> sendMessage());
        inputBar.add(inputField, BorderLayout.CENTER);

        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        JButton sendBtn = new JButton("Send");
        sendBtn.addActionListener(e -> sendMessage());
        JButton runBtn = new JButton("Run");
        runBtn.addActionListener(e -> {
            if (currentSessionId != null) agentLoop.startAsync(currentSessionId);
        });
        buttonPanel.add(sendBtn);
        buttonPanel.add(runBtn);
        inputBar.add(buttonPanel, BorderLayout.EAST);

        add(inputBar, BorderLayout.SOUTH);
    }

    public void setProjectId(String projectId) {
        this.currentSessionId = projectId;
        loadMessages();
        watchEvents();
    }

    private void loadMessages() {
        if (currentSessionId == null) return;
        SwingUtilities.invokeLater(() -> {
            chatPane.setText("");
            var messages = messageRepository.findBySessionIdOrderBySeqAsc(currentSessionId);
            for (MessageDocument msg : messages) {
                appendMessage(msg.getRole(), msg.getContent());
            }
        });
    }

    private void watchEvents() {
        if (currentSessionId == null) return;
        changeStreamService.watchBySessionId("events", EventDocument.class, currentSessionId)
                .subscribe(event -> SwingUtilities.invokeLater(() -> {
                    if (event.getType() == EventType.MODEL_TOKEN_DELTA) {
                        Object payload = event.getPayload();
                        if (payload instanceof java.util.Map<?, ?> map) {
                            Object token = map.get("token");
                            if (token != null) appendToken(token.toString());
                        }
                    } else {
                        appendEvent(event.getType().name());
                    }
                }));
    }

    private void sendMessage() {
        if (currentSessionId == null || inputField.getText().isBlank()) return;
        String content = inputField.getText();
        inputField.setText("");

        MessageDocument msg = new MessageDocument();
        msg.setMessageId(UUID.randomUUID().toString());
        msg.setSessionId(currentSessionId);
        msg.setSeq(messageRepository.countBySessionId(currentSessionId) + 1);
        msg.setRole("user");
        msg.setContent(content);
        msg.setTimestamp(Instant.now());
        messageRepository.save(msg);

        eventService.emit(currentSessionId, EventType.USER_MESSAGE_RECEIVED,
                java.util.Map.of("content", content));
        appendMessage("user", content);
    }

    private void appendMessage(String role, String content) {
        StyledDocument doc = chatPane.getStyledDocument();
        SimpleAttributeSet attrs = new SimpleAttributeSet();
        StyleConstants.setForeground(attrs, role.equals("user")
                ? new Color(0x56, 0x9C, 0xD6)
                : new Color(0xCC, 0xCC, 0xCC));
        try {
            doc.insertString(doc.getLength(), "[" + role + "] " + content + "\n", attrs);
        } catch (BadLocationException ignored) {}
        chatPane.setCaretPosition(doc.getLength());
    }

    private void appendToken(String token) {
        StyledDocument doc = chatPane.getStyledDocument();
        SimpleAttributeSet attrs = new SimpleAttributeSet();
        StyleConstants.setForeground(attrs, new Color(0xCC, 0xCC, 0xCC));
        try {
            doc.insertString(doc.getLength(), token, attrs);
        } catch (BadLocationException ignored) {}
        chatPane.setCaretPosition(doc.getLength());
    }

    private void appendEvent(String eventType) {
        StyledDocument doc = chatPane.getStyledDocument();
        SimpleAttributeSet attrs = new SimpleAttributeSet();
        StyleConstants.setForeground(attrs, new Color(0x80, 0x80, 0x80));
        StyleConstants.setFontSize(attrs, 11);
        try {
            doc.insertString(doc.getLength(), "[" + eventType + "]\n", attrs);
        } catch (BadLocationException ignored) {}
        chatPane.setCaretPosition(doc.getLength());
    }
}
