package io.github.drompincen.javaclawv1.ui.view;

import io.github.drompincen.javaclawv1.persistence.document.IdeaDocument;
import io.github.drompincen.javaclawv1.persistence.repository.IdeaRepository;
import io.github.drompincen.javaclawv1.persistence.stream.ChangeStreamService;
import io.github.drompincen.javaclawv1.protocol.api.IdeaDto;
import io.github.drompincen.javaclawv1.runtime.merge.MergeService;
import org.springframework.stereotype.Component;

import javax.swing.*;
import java.awt.*;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Component
public class IdeasView extends JPanel {

    private final IdeaRepository ideaRepository;
    private final ChangeStreamService changeStreamService;
    private final MergeService mergeService;

    private final JPanel ideaCards = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 8));
    private String projectId;

    public IdeasView(IdeaRepository ideaRepository,
                     ChangeStreamService changeStreamService,
                     MergeService mergeService) {
        this.ideaRepository = ideaRepository;
        this.changeStreamService = changeStreamService;
        this.mergeService = mergeService;
        buildUI();
    }

    private void buildUI() {
        setLayout(new BorderLayout(0, 8));
        setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        JLabel title = new JLabel("Ideas");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 16f));
        JButton newIdeaBtn = new JButton("+ New Idea");
        newIdeaBtn.addActionListener(e -> createIdea());
        toolbar.add(title);
        toolbar.add(newIdeaBtn);
        add(toolbar, BorderLayout.NORTH);

        JScrollPane scrollPane = new JScrollPane(ideaCards);
        scrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        add(scrollPane, BorderLayout.CENTER);
    }

    public void setProjectId(String projectId) {
        this.projectId = projectId;
        refresh();
        changeStreamService.watchByField("ideas", IdeaDocument.class, "projectId", projectId)
                .subscribe(doc -> SwingUtilities.invokeLater(this::refresh));
    }

    private void refresh() {
        if (projectId == null) return;
        List<IdeaDocument> ideas = ideaRepository.findByProjectId(projectId);
        SwingUtilities.invokeLater(() -> {
            ideaCards.removeAll();
            for (IdeaDocument idea : ideas) {
                JPanel card = new JPanel();
                card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
                card.setPreferredSize(new Dimension(220, 100));
                card.setBorder(BorderFactory.createCompoundBorder(
                        BorderFactory.createMatteBorder(0, 3, 0, 0, new Color(0x00, 0x7A, 0xCC)),
                        BorderFactory.createEmptyBorder(8, 8, 8, 8)));

                JLabel titleLabel = new JLabel(idea.getTitle());
                titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD));
                titleLabel.setAlignmentX(LEFT_ALIGNMENT);

                JLabel statusLabel = new JLabel(idea.getStatus().name());
                statusLabel.setForeground(new Color(0x80, 0x80, 0x80));
                statusLabel.setFont(statusLabel.getFont().deriveFont(11f));
                statusLabel.setAlignmentX(LEFT_ALIGNMENT);

                String contentText = idea.getContent() != null
                        ? idea.getContent().substring(0, Math.min(idea.getContent().length(), 100))
                        : "";
                JLabel contentLabel = new JLabel("<html>" + contentText + "</html>");
                contentLabel.setForeground(new Color(0xAA, 0xAA, 0xAA));
                contentLabel.setAlignmentX(LEFT_ALIGNMENT);

                card.add(titleLabel);
                card.add(Box.createVerticalStrut(2));
                card.add(statusLabel);
                card.add(Box.createVerticalStrut(4));
                card.add(contentLabel);

                ideaCards.add(card);
            }
            ideaCards.revalidate();
            ideaCards.repaint();
        });
    }

    private void createIdea() {
        if (projectId == null) return;
        String ideaTitle = JOptionPane.showInputDialog(this, "Idea title:", "New Idea", JOptionPane.PLAIN_MESSAGE);
        if (ideaTitle != null && !ideaTitle.isBlank()) {
            IdeaDocument idea = new IdeaDocument();
            idea.setIdeaId(UUID.randomUUID().toString());
            idea.setProjectId(projectId);
            idea.setTitle(ideaTitle);
            idea.setStatus(IdeaDto.IdeaStatus.NEW);
            idea.setCreatedAt(Instant.now());
            idea.setUpdatedAt(Instant.now());
            ideaRepository.save(idea);
            refresh();
        }
    }
}
