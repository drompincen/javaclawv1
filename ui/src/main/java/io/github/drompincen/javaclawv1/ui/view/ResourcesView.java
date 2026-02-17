package io.github.drompincen.javaclawv1.ui.view;

import io.github.drompincen.javaclawv1.persistence.document.ResourceDocument;
import io.github.drompincen.javaclawv1.runtime.resource.ResourceService;
import org.springframework.stereotype.Component;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.util.Map;

@Component
public class ResourcesView extends JPanel {

    private final ResourceService resourceService;
    private final DefaultTableModel tableModel;
    private final JTable table;

    public ResourcesView(ResourceService resourceService) {
        this.resourceService = resourceService;
        setLayout(new BorderLayout(0, 8));
        setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        JLabel title = new JLabel("Resources");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 16f));
        add(title, BorderLayout.NORTH);

        tableModel = new DefaultTableModel(new String[]{"Name", "Role", "Workload"}, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        table = new JTable(tableModel);
        table.getColumnModel().getColumn(0).setPreferredWidth(150);
        table.getColumnModel().getColumn(1).setPreferredWidth(100);
        table.getColumnModel().getColumn(2).setPreferredWidth(150);
        add(new JScrollPane(table), BorderLayout.CENTER);
    }

    public void refresh() {
        SwingUtilities.invokeLater(() -> {
            tableModel.setRowCount(0);
            Map<String, Double> workloads = resourceService.getWorkloads();
            for (ResourceDocument res : resourceService.findAll()) {
                double wl = workloads.getOrDefault(res.getResourceId(), 0.0);
                tableModel.addRow(new Object[]{
                        res.getName(),
                        res.getRole() != null ? res.getRole().name() : "",
                        String.format("%.0f%%", wl)
                });
            }
        });
    }
}
