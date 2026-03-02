package io.github.drompincen.javaclawv1.gateway;

import io.github.bonigarcia.wdm.WebDriverManager;
import io.github.drompincen.javaclawv1.gateway.test.TestMongoConfiguration;
import io.github.drompincen.javaclawv1.runtime.agent.llm.ScenarioRunner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.Select;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.io.File;
import java.net.URL;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "javaclaw.llm.provider=test",
                "javaclaw.scenario.autorun=false",
                "javaclaw.scheduler.enabled=false"
        }
)
@ActiveProfiles("scenario")
@Import(TestMongoConfiguration.class)
class ScenarioUiIntegrationTest {

    @LocalServerPort
    int port;

    @Autowired
    ScenarioRunner scenarioRunner;

    WebDriver driver;

    static boolean chromeAvailable = false;

    @BeforeAll
    static void setupDriver() {
        System.setProperty("javaclaw.llm.provider", "test");
        try {
            WebDriverManager.chromedriver().setup();
            // Verify Chrome actually works by attempting to create a driver
            ChromeDriver testDriver = new ChromeDriver(headlessOpts());
            testDriver.quit();
            chromeAvailable = true;
        } catch (Exception e) {
            System.err.println("Chrome/ChromeDriver not available — UI tests will be skipped: " + e.getMessage());
            chromeAvailable = false;
        }
    }

    private static ChromeOptions headlessOpts() {
        ChromeOptions options = new ChromeOptions();
        options.addArguments("--headless=new", "--no-sandbox", "--disable-dev-shm-usage",
                "--disable-gpu", "--window-size=1920,1080");
        return options;
    }

    @BeforeEach
    void openBrowser() {
        assumeTrue(chromeAvailable, "Chrome/ChromeDriver not available — skipping UI test");
        scenarioRunner.setServerPort(port);
        scenarioRunner.cleanMongoDB();
        driver = new ChromeDriver(headlessOpts());
    }

    @AfterEach
    void closeBrowser() {
        if (driver != null) {
            driver.quit();
        }
    }

    @Test
    void intakeScenario_uiShowsThreadsAndTickets() {
        // Run a scenario that creates threads + tickets via intake pipeline
        String filePath = resolveScenarioFile("scenario-generalist-intake");
        assertNotNull(filePath, "scenario-generalist-intake.json not found");
        assertTrue(scenarioRunner.runSingleScenario(filePath), "scenario-generalist-intake failed");

        // Open UI and wait for WebSocket
        driver.get("http://localhost:" + port + "/index.html");
        waitForWsLive();

        // Select the project created by the scenario
        selectFirstProject();

        // Assert: threads badge >= 1
        assertBadgeGte("#navThreadsBadge", 1, "threads badge");

        // Assert: tickets badge >= 1
        assertBadgeGte("#navTicketsBadge", 1, "tickets badge");
    }

    @Test
    void seededProject_uiShowsAllEntityBadges() {
        // Run ask-claw scenario which seeds objectives, tickets, resources, threads
        String filePath = resolveScenarioFile("scenario-ask-claw");
        assertNotNull(filePath, "scenario-ask-claw.json not found");
        assertTrue(scenarioRunner.runSingleScenario(filePath), "scenario-ask-claw failed");

        // Open UI
        driver.get("http://localhost:" + port + "/index.html");
        waitForWsLive();
        selectFirstProject();

        // Assert: badges for multiple entity types
        assertBadgeGte("#navThreadsBadge", 1, "threads badge");
        assertBadgeGte("#navTicketsBadge", 1, "tickets badge");
        assertBadgeGte("#navObjBadge", 1, "objectives badge");
        assertBadgeGte("#navResBadge", 1, "resources badge");
    }

    @Test
    void agentTiles_renderedInLogView() {
        // Open UI directly — agents are always seeded
        driver.get("http://localhost:" + port + "/index.html");
        waitForWsLive();

        // Navigate to log view
        WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(10));
        WebElement logNav = wait.until(
                ExpectedConditions.elementToBeClickable(By.cssSelector(".navItem[data-view='log']")));
        logNav.click();

        // Assert: log container exists
        WebElement log = wait.until(ExpectedConditions.presenceOfElementLocated(By.id("log")));
        assertNotNull(log, "#log element should exist");

        // Assert: at least one agent tile rendered
        List<WebElement> tiles = driver.findElements(By.cssSelector(".agent-tile"));
        assertFalse(tiles.isEmpty(), "Expected at least one .agent-tile element");
    }

    @Test
    void viewNavigation_switchesCenterTitle() {
        driver.get("http://localhost:" + port + "/index.html");
        waitForWsLive();

        WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(10));

        // Navigate to intake view
        navigateToView(wait, "intake", "INTAKE");

        // Navigate to threads view
        navigateToView(wait, "threads", "THREADS");

        // Navigate to tickets view
        navigateToView(wait, "tickets", "TICKETS");
    }

    @Test
    void intakeElements_exist() {
        driver.get("http://localhost:" + port + "/index.html");
        waitForWsLive();

        WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(10));

        // Navigate to intake view
        WebElement intakeNav = wait.until(
                ExpectedConditions.elementToBeClickable(By.cssSelector(".navItem[data-view='intake']")));
        intakeNav.click();

        // Assert intake textarea exists
        WebElement intakeText = wait.until(ExpectedConditions.presenceOfElementLocated(By.id("intakeText")));
        assertNotNull(intakeText, "#intakeText should exist");

        // Assert intake send button exists
        WebElement intakeSend = wait.until(ExpectedConditions.presenceOfElementLocated(By.id("intakeSend")));
        assertNotNull(intakeSend, "#intakeSend should exist");
    }

    // ── Helpers ──


    private void waitForWsLive() {
        WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(15));
        wait.until(d -> {
            try {
                WebElement badge = d.findElement(By.id("wsBadge"));
                return badge.getText().contains("WS LIVE");
            } catch (Exception e) {
                return false;
            }
        });
    }

    private void selectFirstProject() {
        WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(10));
        WebElement projectSelect = wait.until(
                ExpectedConditions.presenceOfElementLocated(By.id("projectSelect")));
        Select select = new Select(projectSelect);
        // Wait for options to be populated (more than just the default)
        wait.until(d -> {
            Select s = new Select(d.findElement(By.id("projectSelect")));
            return s.getOptions().size() > 1;
        });
        select = new Select(driver.findElement(By.id("projectSelect")));
        select.selectByIndex(1);
        // Trigger change event via JavaScript
        ((org.openqa.selenium.JavascriptExecutor) driver)
                .executeScript("arguments[0].dispatchEvent(new Event('change'))", projectSelect);
        // Brief wait for data to load
        try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
    }

    private void assertBadgeGte(String selector, int minimum, String label) {
        WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(10));
        wait.until(d -> {
            try {
                WebElement el = d.findElement(By.cssSelector(selector));
                String text = el.getText().trim();
                if (text.isEmpty()) return false;
                int count = Integer.parseInt(text.replaceAll("[^0-9]", ""));
                return count >= minimum;
            } catch (Exception e) {
                return false;
            }
        });
        WebElement el = driver.findElement(By.cssSelector(selector));
        int count = Integer.parseInt(el.getText().trim().replaceAll("[^0-9]", ""));
        assertTrue(count >= minimum, label + ": expected >= " + minimum + " but got " + count);
    }

    private void navigateToView(WebDriverWait wait, String viewKey, String expectedTitle) {
        WebElement nav = wait.until(
                ExpectedConditions.elementToBeClickable(
                        By.cssSelector(".navItem[data-view='" + viewKey + "']")));
        nav.click();
        wait.until(d -> {
            try {
                WebElement title = d.findElement(By.id("centerTitle"));
                return title.getText().contains(expectedTitle);
            } catch (Exception e) {
                return false;
            }
        });
    }

    private String resolveScenarioFile(String scenarioName) {
        String fileName = scenarioName + ".json";
        URL url = getClass().getClassLoader().getResource(fileName);
        if (url != null) {
            return url.getPath();
        }
        File f = new File("scenario-testing/scenarios/" + fileName);
        if (f.exists()) {
            return f.getAbsolutePath();
        }
        return null;
    }
}
