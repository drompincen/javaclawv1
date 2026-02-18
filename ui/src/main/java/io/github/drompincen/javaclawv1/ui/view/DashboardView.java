package io.github.drompincen.javaclawv1.ui.view;

import io.github.drompincen.javaclawv1.persistence.repository.TicketRepository;
import io.github.drompincen.javaclawv1.protocol.api.TicketDto;
import org.springframework.stereotype.Component;

import javax.swing.*;
import java.awt.*;

@Component
public class DashboardView extends JPanel {

    private final TicketRepository ticketRepository;

    private final JLabel todoCount = new JLabel("TODO: 0");
    private final JLabel inProgressCount = new JLabel("In Progress: 0");
    private final JLabel doneCount = new JLabel("Done: 0");
    private String projectId;

    public DashboardView(TicketRepository ticketRepository) {
        this.ticketRepository = ticketRepository;
        buildUI();
    }

    private void buildUI() {
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));

        JLabel title = new JLabel("Project Dashboard");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 18f));
        title.setAlignmentX(LEFT_ALIGNMENT);
        add(title);
        add(Box.createVerticalStrut(16));

        JPanel metricsPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 20, 0));
        metricsPanel.setAlignmentX(LEFT_ALIGNMENT);
        metricsPanel.add(new JLabel("Tickets:"));
        metricsPanel.add(todoCount);
        metricsPanel.add(inProgressCount);
        metricsPanel.add(doneCount);
        add(metricsPanel);

        add(Box.createVerticalGlue());
    }

    public void setProjectId(String projectId) {
        this.projectId = projectId;
        refresh();
    }

    private void refresh() {
        if (projectId == null) return;

        SwingUtilities.invokeLater(() -> {
            long todo = ticketRepository.findByProjectIdAndStatus(projectId, TicketDto.TicketStatus.TODO).size();
            long inProg = ticketRepository.findByProjectIdAndStatus(projectId, TicketDto.TicketStatus.IN_PROGRESS).size();
            long done = ticketRepository.findByProjectIdAndStatus(projectId, TicketDto.TicketStatus.DONE).size();
            todoCount.setText("TODO: " + todo);
            inProgressCount.setText("In Progress: " + inProg);
            doneCount.setText("Done: " + done);
        });
    }
}
