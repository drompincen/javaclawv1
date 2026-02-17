package io.github.drompincen.javaclawv1.ui.view;

import io.github.drompincen.javaclawv1.persistence.document.TicketDocument;
import io.github.drompincen.javaclawv1.persistence.repository.TicketRepository;
import io.github.drompincen.javaclawv1.persistence.stream.ChangeStreamService;
import io.github.drompincen.javaclawv1.protocol.api.TicketDto;
import org.springframework.stereotype.Component;

import javax.swing.*;
import java.awt.*;
import java.util.List;

@Component
public class BoardView extends JPanel {

    private final TicketRepository ticketRepository;
    private final ChangeStreamService changeStreamService;
    private String projectId;

    public BoardView(TicketRepository ticketRepository, ChangeStreamService changeStreamService) {
        this.ticketRepository = ticketRepository;
        this.changeStreamService = changeStreamService;

        setLayout(new GridLayout(1, 0, 8, 0));
        setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        for (TicketDto.TicketStatus status : TicketDto.TicketStatus.values()) {
            add(createColumn(status));
        }
    }

    private JPanel createColumn(TicketDto.TicketStatus status) {
        JPanel column = new JPanel();
        column.setLayout(new BoxLayout(column, BoxLayout.Y_AXIS));
        column.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(UIManager.getColor("Component.borderColor"), 1),
                BorderFactory.createEmptyBorder(8, 8, 8, 8)));

        JLabel header = new JLabel(status.name());
        header.setFont(header.getFont().deriveFont(Font.BOLD));
        header.setAlignmentX(LEFT_ALIGNMENT);
        column.add(header);
        column.add(Box.createVerticalStrut(8));

        column.putClientProperty("status", status);
        return column;
    }

    public void setProjectId(String projectId) {
        this.projectId = projectId;
        refresh();
        changeStreamService.watchByField("tickets", TicketDocument.class, "projectId", projectId)
                .subscribe(doc -> SwingUtilities.invokeLater(this::refresh));
    }

    private void refresh() {
        if (projectId == null) return;
        List<TicketDocument> tickets = ticketRepository.findByProjectId(projectId);

        SwingUtilities.invokeLater(() -> {
            for (java.awt.Component comp : getComponents()) {
                if (comp instanceof JPanel column) {
                    TicketDto.TicketStatus status = (TicketDto.TicketStatus) ((JComponent) column).getClientProperty("status");
                    if (status == null) continue;

                    // Keep header and strut, remove ticket cards
                    while (column.getComponentCount() > 2) {
                        column.remove(column.getComponentCount() - 1);
                    }

                    tickets.stream()
                            .filter(t -> t.getStatus() == status)
                            .forEach(t -> {
                                JLabel card = new JLabel(t.getTitle());
                                card.setAlignmentX(LEFT_ALIGNMENT);
                                card.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
                                card.setBorder(BorderFactory.createCompoundBorder(
                                        BorderFactory.createMatteBorder(0, 3, 0, 0, new Color(0x00, 0x7A, 0xCC)),
                                        BorderFactory.createEmptyBorder(6, 8, 6, 8)));
                                column.add(card);
                                column.add(Box.createVerticalStrut(4));
                            });

                    column.add(Box.createVerticalGlue());
                }
            }
            revalidate();
            repaint();
        });
    }
}
