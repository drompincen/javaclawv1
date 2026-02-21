package io.github.drompincen.javaclawv1.runtime.agent;

import io.github.drompincen.javaclawv1.persistence.document.TicketDocument;
import io.github.drompincen.javaclawv1.persistence.repository.TicketRepository;
import io.github.drompincen.javaclawv1.protocol.api.TicketDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/**
 * @deprecated Use the intake pipeline with generalist agent instead.
 * LLM handles all format parsing including CSV, Excel, and Jira exports.
 * This class will be removed in a future release.
 */
@Deprecated
@Service
public class JiraImportService {

    private static final Logger log = LoggerFactory.getLogger(JiraImportService.class);

    private final TicketRepository ticketRepository;

    public JiraImportService(TicketRepository ticketRepository) {
        this.ticketRepository = ticketRepository;
    }

    // -----------------------------------------------------------------------
    // Inner record for results
    // -----------------------------------------------------------------------

    public record ImportResult(int total, int imported, List<String> errors) {}

    // -----------------------------------------------------------------------
    // 1. importFile — reads CSV or Excel and creates tickets
    // -----------------------------------------------------------------------

    public ImportResult importFile(String filePath, String projectId) {
        Path path = Path.of(filePath);
        if (!Files.exists(path)) {
            return new ImportResult(0, 0, List.of("File not found: " + filePath));
        }
        String name = path.getFileName().toString().toLowerCase();
        try {
            if (name.endsWith(".csv")) {
                return importCsv(path, projectId);
            }
            if (name.endsWith(".xlsx") || name.endsWith(".xls")) {
                return importExcel(path, projectId);
            }
            return new ImportResult(0, 0, List.of("Unsupported file type: " + name + ". Use .csv, .xlsx, or .xls"));
        } catch (Exception e) {
            log.error("Import error for file {}: {}", filePath, e.getMessage(), e);
            return new ImportResult(0, 0, List.of("Import error: " + e.getMessage()));
        }
    }

    // -----------------------------------------------------------------------
    // 2. isImportRequest — detect Jira/Excel/CSV import requests
    // -----------------------------------------------------------------------

    public boolean isImportRequest(String lower) {
        boolean hasImportVerb = lower.contains("import") || lower.contains("load") || lower.contains("ingest")
                || lower.contains("read") || lower.contains("parse") || lower.contains("create tickets from");
        boolean hasImportContext = lower.contains("jira") || lower.contains("excel") || lower.contains(".xlsx")
                || lower.contains(".xls") || lower.contains(".csv") || lower.contains("spreadsheet")
                || (lower.contains("ticket") && (lower.contains("file") || lower.contains("dump") || lower.contains("export")));
        return hasImportVerb && hasImportContext;
    }

    // -----------------------------------------------------------------------
    // 3. executeJiraImport — orchestrates import
    // -----------------------------------------------------------------------

    public String executeJiraImport(String userMessage, String sessionId,
                                    Function<String, String> projectIdResolver,
                                    Function<String, List<String>> pathExtractor) {
        List<String> paths = pathExtractor.apply(userMessage);
        if (paths.isEmpty()) {
            return "I need a file path to import from. Please provide the full path to a CSV or Excel file exported from Jira.\n\n"
                    + "**Example:** `import tickets from /path/to/jira-export.csv`";
        }

        String projectId = projectIdResolver.apply(sessionId);
        if (projectId == null) {
            return "**Error:** Could not determine the project for this session. "
                    + "Please use a thread that belongs to a project, or specify the project.";
        }

        String filePath = paths.get(0);
        log.info("[JiraImport] file={} project={}", filePath, projectId);
        ImportResult result = importFile(filePath, projectId);

        StringBuilder sb = new StringBuilder();
        sb.append("**Jira Import Results**\n\n");
        sb.append("- **File:** `").append(filePath).append("`\n");
        sb.append("- **Imported:** ").append(result.imported()).append(" / ").append(result.total()).append(" rows\n");
        if (!result.errors().isEmpty()) {
            sb.append("\n**Errors:**\n");
            int shown = Math.min(result.errors().size(), 10);
            for (int i = 0; i < shown; i++) {
                sb.append("- ").append(result.errors().get(i)).append("\n");
            }
            if (result.errors().size() > 10) {
                sb.append("- ... and ").append(result.errors().size() - 10).append(" more errors\n");
            }
        }
        if (result.imported() > 0) {
            sb.append("\nTickets have been created in the project. Use the ticket list to review them.");
        }
        log.info("[JiraImport] imported={}/{} errors={}", result.imported(), result.total(), result.errors().size());
        return sb.toString();
    }

    // -----------------------------------------------------------------------
    // CSV import
    // -----------------------------------------------------------------------

    private ImportResult importCsv(Path path, String projectId) throws Exception {
        List<String> lines;
        try (BufferedReader reader = Files.newBufferedReader(path)) {
            lines = reader.lines().toList();
        }
        if (lines.isEmpty()) {
            return new ImportResult(0, 0, List.of("CSV file is empty"));
        }

        String[] headers = parseCsvLine(lines.get(0));
        Map<String, Integer> colMap = mapColumns(headers);
        if (colMap.get("title") < 0) {
            return new ImportResult(0, 0,
                    List.of("No 'Summary' or 'Title' column found. Headers: " + String.join(", ", headers)));
        }

        int imported = 0;
        List<String> errors = new ArrayList<>();
        for (int i = 1; i < lines.size(); i++) {
            try {
                String[] cols = parseCsvLine(lines.get(i));
                if (cols.length == 0 || (cols.length == 1 && cols[0].isBlank())) {
                    continue;
                }
                TicketDocument ticket = buildTicket(cols, colMap, projectId);
                ticketRepository.save(ticket);
                imported++;
            } catch (Exception e) {
                errors.add("Row " + (i + 1) + ": " + e.getMessage());
            }
        }
        return new ImportResult(lines.size() - 1, imported, errors);
    }

    // -----------------------------------------------------------------------
    // Excel import (Apache POI — degrades gracefully if not on classpath)
    // -----------------------------------------------------------------------

    private ImportResult importExcel(Path path, String projectId) throws Exception {
        try {
            return doImportExcel(path, projectId);
        } catch (NoClassDefFoundError | Exception e) {
            if (e instanceof NoClassDefFoundError) {
                log.warn("Apache POI is not available on the classpath. Excel import is disabled.");
                return new ImportResult(0, 0,
                        List.of("Excel import requires Apache POI on the classpath. Please add poi-ooxml dependency."));
            }
            throw e;
        }
    }

    private ImportResult doImportExcel(Path path, String projectId) throws Exception {
        List<String> errors = new ArrayList<>();
        int imported = 0;
        int totalRows = 0;

        try (var workbook = org.apache.poi.ss.usermodel.WorkbookFactory.create(path.toFile())) {
            var sheet = workbook.getSheetAt(0);
            var headerRow = sheet.getRow(0);
            if (headerRow == null) {
                return new ImportResult(0, 0, List.of("Excel sheet has no header row"));
            }

            String[] headers = new String[headerRow.getLastCellNum()];
            for (int c = 0; c < headers.length; c++) {
                var cell = headerRow.getCell(c);
                headers[c] = cell != null ? getCellString(cell) : "";
            }
            Map<String, Integer> colMap = mapColumns(headers);
            if (colMap.get("title") < 0) {
                return new ImportResult(0, 0,
                        List.of("No 'Summary' or 'Title' column found. Headers: " + String.join(", ", headers)));
            }

            for (int r = 1; r <= sheet.getLastRowNum(); r++) {
                var row = sheet.getRow(r);
                if (row == null) {
                    continue;
                }
                totalRows++;
                try {
                    String[] cols = new String[row.getLastCellNum()];
                    for (int c = 0; c < cols.length; c++) {
                        var cell = row.getCell(c);
                        cols[c] = cell != null ? getCellString(cell) : "";
                    }
                    if (cols.length == 0
                            || (cols.length > 0 && cols[0].isBlank() && getCol(cols, colMap.get("title")).isBlank())) {
                        continue;
                    }
                    TicketDocument ticket = buildTicket(cols, colMap, projectId);
                    ticketRepository.save(ticket);
                    imported++;
                } catch (Exception e) {
                    errors.add("Row " + (r + 1) + ": " + e.getMessage());
                }
            }
        }
        return new ImportResult(totalRows, imported, errors);
    }

    private String getCellString(org.apache.poi.ss.usermodel.Cell cell) {
        return switch (cell.getCellType()) {
            case STRING -> cell.getStringCellValue();
            case NUMERIC -> {
                if (org.apache.poi.ss.usermodel.DateUtil.isCellDateFormatted(cell)) {
                    yield cell.getLocalDateTimeCellValue().toString();
                }
                double d = cell.getNumericCellValue();
                yield d == Math.floor(d) ? String.valueOf((long) d) : String.valueOf(d);
            }
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            case FORMULA -> cell.getCellFormula();
            default -> "";
        };
    }

    // -----------------------------------------------------------------------
    // Column mapping — maps common Jira CSV/Excel column names to ticket fields
    // -----------------------------------------------------------------------

    private Map<String, Integer> mapColumns(String[] headers) {
        Map<String, Integer> map = new HashMap<>();
        map.put("title", -1);
        map.put("description", -1);
        map.put("priority", -1);
        map.put("status", -1);
        map.put("key", -1);

        for (int i = 0; i < headers.length; i++) {
            String h = headers[i].trim().toLowerCase();
            if (h.equals("summary") || h.equals("title") || h.equals("issue summary")) {
                map.put("title", i);
            } else if (h.equals("description") || h.equals("issue description")) {
                map.put("description", i);
            } else if (h.equals("priority")) {
                map.put("priority", i);
            } else if (h.equals("status")) {
                map.put("status", i);
            } else if (h.equals("issue key") || h.equals("key") || h.equals("issue id")) {
                map.put("key", i);
            }
        }
        return map;
    }

    // -----------------------------------------------------------------------
    // Ticket construction from parsed row data
    // -----------------------------------------------------------------------

    private TicketDocument buildTicket(String[] cols, Map<String, Integer> colMap, String projectId) {
        TicketDocument ticket = new TicketDocument();
        ticket.setTicketId(UUID.randomUUID().toString());
        ticket.setProjectId(projectId);

        String title = getCol(cols, colMap.get("title"));
        if (title.isBlank()) {
            throw new RuntimeException("Empty title");
        }

        // Map Jira key as prefix to title if present
        String key = getCol(cols, colMap.get("key"));
        if (!key.isBlank()) {
            title = "[" + key + "] " + title;
        }
        ticket.setTitle(title);

        ticket.setDescription(getCol(cols, colMap.get("description")));

        // Map priority
        String prio = getCol(cols, colMap.get("priority")).toUpperCase();
        ticket.setPriority(switch (prio) {
            case "HIGHEST", "BLOCKER", "CRITICAL" -> TicketDto.TicketPriority.CRITICAL;
            case "HIGH", "MAJOR" -> TicketDto.TicketPriority.HIGH;
            case "LOW", "MINOR" -> TicketDto.TicketPriority.LOW;
            case "LOWEST", "TRIVIAL" -> TicketDto.TicketPriority.LOW;
            default -> TicketDto.TicketPriority.MEDIUM;
        });

        // Map status
        String st = getCol(cols, colMap.get("status")).toUpperCase().replace(" ", "_");
        ticket.setStatus(switch (st) {
            case "IN_PROGRESS", "IN_REVIEW", "IN_DEVELOPMENT" -> TicketDto.TicketStatus.IN_PROGRESS;
            case "DONE", "RESOLVED", "CLOSED", "RELEASED" -> TicketDto.TicketStatus.DONE;
            default -> TicketDto.TicketStatus.TODO;
        });

        ticket.setCreatedAt(Instant.now());
        ticket.setUpdatedAt(Instant.now());
        return ticket;
    }

    private String getCol(String[] cols, int idx) {
        if (idx < 0 || idx >= cols.length) {
            return "";
        }
        return cols[idx] != null ? cols[idx].trim() : "";
    }

    // -----------------------------------------------------------------------
    // Simple CSV line parser (handles quoted fields with commas)
    // -----------------------------------------------------------------------

    private String[] parseCsvLine(String line) {
        List<String> fields = new ArrayList<>();
        boolean inQuotes = false;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                inQuotes = !inQuotes;
                continue;
            }
            if (c == ',' && !inQuotes) {
                fields.add(sb.toString());
                sb.setLength(0);
                continue;
            }
            sb.append(c);
        }
        fields.add(sb.toString());
        return fields.toArray(new String[0]);
    }
}
