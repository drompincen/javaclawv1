package io.github.drompincen.javaclawv1.ui;

import com.formdev.flatlaf.FlatDarkLaf;
import io.github.drompincen.javaclawv1.ui.view.MainWindow;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.data.mongodb.repository.config.EnableMongoRepositories;

import javax.swing.*;

@SpringBootApplication(scanBasePackages = "io.github.drompincen.javaclawv1")
@EnableMongoRepositories(basePackages = "io.github.drompincen.javaclawv1.persistence.repository")
public class JavaClawDesktopApp {

    public static void main(String[] args) {
        FlatDarkLaf.setup();

        UIManager.put("Component.focusWidth", 1);
        UIManager.put("Button.arc", 6);
        UIManager.put("TextComponent.arc", 6);
        UIManager.put("Component.arrowType", "triangle");

        ConfigurableApplicationContext ctx = SpringApplication.run(JavaClawDesktopApp.class, args);

        SwingUtilities.invokeLater(() -> {
            MainWindow mainWindow = ctx.getBean(MainWindow.class);
            JFrame frame = new JFrame("JavaClaw");
            frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            frame.setContentPane(mainWindow);
            frame.setSize(1400, 900);
            frame.setLocationRelativeTo(null);
            frame.setVisible(true);

            frame.addWindowListener(new java.awt.event.WindowAdapter() {
                @Override
                public void windowClosing(java.awt.event.WindowEvent e) {
                    ctx.close();
                }
            });
        });
    }
}
