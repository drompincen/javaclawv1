package io.github.drompincen.javaclawv1.ui.view;

import io.github.drompincen.javaclawv1.persistence.document.ProjectDocument;
import io.github.drompincen.javaclawv1.persistence.repository.ProjectRepository;
import io.github.drompincen.javaclawv1.persistence.stream.ChangeStreamService;
import io.github.drompincen.javaclawv1.protocol.api.ProjectDto;
import org.springframework.stereotype.Component;

import javax.swing.*;
import java.awt.*;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.annotation.PostConstruct;

@Component
public class MainWindow extends JPanel {

    private final ProjectRepository projectRepository;
    private final ChangeStreamService changeStreamService;
    private final ThreadView threadView;
    private final BoardView boardView;
    private final DashboardView dashboardView;
    private final ResourcesView resourcesView;
    private final IdeasView ideasView;

    private final DefaultListModel<String> projectListModel = new DefaultListModel<>();
    private final JList<String> projectList = new JList<>(projectListModel);
    private final JLabel statusLabel = new JLabel("Ready");
    private final JLabel mongoStatusLabel = new JLabel("MongoDB: Connected");
    private final JLabel threadCountLabel = new JLabel("Active Threads: 0");
    private String selectedProjectId;

    public MainWindow(ProjectRepository projectRepository,
                      ChangeStreamService changeStreamService,
                      ThreadView threadView,
                      BoardView boardView,
                      DashboardView dashboardView,
                      ResourcesView resourcesView,
                      IdeasView ideasView) {
        this.projectRepository = projectRepository;
        this.changeStreamService = changeStreamService;
        this.threadView = threadView;
        this.boardView = boardView;
        this.dashboardView = dashboardView;
        this.resourcesView = resourcesView;
        this.ideasView = ideasView;
    }

    @PostConstruct
    public void init() {
        setLayout(new BorderLayout());
        buildLayout();
        loadProjects();
        watchProjects();
    }

    private void buildLayout() {
        // Left sidebar: project list
        JPanel sidebar = new JPanel(new BorderLayout(0, 8));
        sidebar.setPreferredSize(new Dimension(180, 0));
        sidebar.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        JLabel projectsLabel = new JLabel("Projects");
        projectsLabel.setFont(projectsLabel.getFont().deriveFont(Font.BOLD, 14f));
        sidebar.add(projectsLabel, BorderLayout.NORTH);

        projectList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        projectList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                String val = projectList.getSelectedValue();
                if (val != null) selectProject(val);
            }
        });
        sidebar.add(new JScrollPane(projectList), BorderLayout.CENTER);

        JButton newProjectBtn = new JButton("+ New");
        newProjectBtn.addActionListener(e -> createProject());
        sidebar.add(newProjectBtn, BorderLayout.SOUTH);

        add(sidebar, BorderLayout.WEST);

        // Center: tabs
        JTabbedPane tabPane = new JTabbedPane();
        tabPane.addTab("Thread", threadView);
        tabPane.addTab("Board", boardView);
        tabPane.addTab("Dashboard", dashboardView);
        tabPane.addTab("Resources", resourcesView);
        tabPane.addTab("Ideas", ideasView);
        add(tabPane, BorderLayout.CENTER);

        // Bottom: status bar
        JPanel statusBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 20, 2));
        statusBar.add(statusLabel);
        statusBar.add(mongoStatusLabel);
        statusBar.add(threadCountLabel);
        add(statusBar, BorderLayout.SOUTH);
    }

    private void loadProjects() {
        List<ProjectDocument> projects = projectRepository.findAllByOrderByUpdatedAtDesc();
        SwingUtilities.invokeLater(() -> {
            projectListModel.clear();
            for (ProjectDocument p : projects) {
                projectListModel.addElement(p.getName() + " [" + p.getProjectId().substring(0, 8) + "]");
            }
        });
    }

    private void watchProjects() {
        changeStreamService.watchCollection("projects", ProjectDocument.class)
                .subscribe(doc -> SwingUtilities.invokeLater(this::loadProjects));
    }

    private void selectProject(String display) {
        String idPart = display.substring(display.lastIndexOf('[') + 1, display.lastIndexOf(']'));
        projectRepository.findAll().stream()
                .filter(p -> p.getProjectId().startsWith(idPart))
                .findFirst()
                .ifPresent(p -> {
                    selectedProjectId = p.getProjectId();
                    threadView.setProjectId(selectedProjectId);
                    boardView.setProjectId(selectedProjectId);
                    dashboardView.setProjectId(selectedProjectId);
                    resourcesView.refresh();
                    ideasView.setProjectId(selectedProjectId);
                    statusLabel.setText("Project: " + p.getName());
                });
    }

    private void createProject() {
        String name = JOptionPane.showInputDialog(this, "Enter project name:", "Create Project", JOptionPane.PLAIN_MESSAGE);
        if (name != null && !name.isBlank()) {
            ProjectDocument doc = new ProjectDocument();
            doc.setProjectId(UUID.randomUUID().toString());
            doc.setName(name);
            doc.setStatus(ProjectDto.ProjectStatus.ACTIVE);
            doc.setCreatedAt(Instant.now());
            doc.setUpdatedAt(Instant.now());
            projectRepository.save(doc);
            loadProjects();
        }
    }
}
