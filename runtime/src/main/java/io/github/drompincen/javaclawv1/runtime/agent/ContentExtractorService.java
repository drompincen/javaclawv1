package io.github.drompincen.javaclawv1.runtime.agent;

import org.apache.poi.ss.usermodel.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.FileInputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Extracts text content from uploaded files based on extension.
 * Supports Excel (.xlsx/.xls), CSV, XML, JSON, TXT, MD, and HTML.
 */
@Service
public class ContentExtractorService {

    private static final Logger log = LoggerFactory.getLogger(ContentExtractorService.class);
    private static final int MAX_FILE_SIZE = 5 * 1024 * 1024; // 5 MB

    public String extractContent(java.util.List<String> filePaths) {
        StringBuilder sb = new StringBuilder();
        for (String path : filePaths) {
            String ext = getExtension(path).toLowerCase();
            String fileName = fileName(path);
            sb.append("\n--- FILE: ").append(fileName)
              .append(" (").append(ext).append(") ---\n");
            try {
                switch (ext) {
                    case "xlsx", "xls" -> sb.append(extractExcel(path));
                    case "csv", "xml", "json", "txt", "md" -> sb.append(readTextFile(path));
                    case "html", "htm" -> sb.append(stripHtmlTags(readTextFile(path)));
                    default -> sb.append("[unsupported format: ").append(ext).append("]");
                }
            } catch (Exception e) {
                log.warn("Failed to extract content from {}: {}", path, e.getMessage());
                sb.append("[error reading file: ").append(e.getMessage()).append("]");
            }
        }
        return sb.toString();
    }

    private String extractExcel(String filePath) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (FileInputStream fis = new FileInputStream(filePath);
             Workbook workbook = WorkbookFactory.create(fis)) {
            for (int s = 0; s < workbook.getNumberOfSheets(); s++) {
                Sheet sheet = workbook.getSheetAt(s);
                sb.append("\n[Sheet: ").append(sheet.getSheetName()).append("]\n");
                for (Row row : sheet) {
                    for (int c = 0; c < row.getLastCellNum(); c++) {
                        if (c > 0) sb.append('\t');
                        Cell cell = row.getCell(c, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK);
                        sb.append(getCellValue(cell));
                    }
                    sb.append('\n');
                }
            }
        }
        return sb.toString();
    }

    private String readTextFile(String filePath) throws Exception {
        Path path = Path.of(filePath);
        long size = Files.size(path);
        if (size > MAX_FILE_SIZE) {
            return "[file too large: " + (size / 1024) + " KB, max " + (MAX_FILE_SIZE / 1024) + " KB]";
        }
        return Files.readString(path);
    }

    private String stripHtmlTags(String content) {
        return content.replaceAll("<[^>]+>", " ").replaceAll("\\s{2,}", " ").trim();
    }

    private String getCellValue(Cell cell) {
        if (cell == null) return "";
        return switch (cell.getCellType()) {
            case STRING -> cell.getStringCellValue();
            case NUMERIC -> {
                if (DateUtil.isCellDateFormatted(cell)) {
                    yield cell.getLocalDateTimeCellValue().toString();
                }
                double val = cell.getNumericCellValue();
                yield val == Math.floor(val) ? String.valueOf((long) val) : String.valueOf(val);
            }
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            case FORMULA -> {
                try { yield String.valueOf(cell.getNumericCellValue()); }
                catch (Exception e) {
                    try { yield cell.getStringCellValue(); }
                    catch (Exception e2) { yield cell.getCellFormula(); }
                }
            }
            case BLANK -> "";
            default -> "";
        };
    }

    /**
     * Detects the format of pasted/raw text content by sniffing its structure.
     * Returns a format hint string (e.g., "JSON", "CSV", "XML", "HTML", "Markdown")
     * or "plain text" if no specific format is detected.
     */
    public String detectTextFormat(String content) {
        if (content == null || content.isBlank()) return "plain text";
        String trimmed = content.strip();

        // JSON: starts with { or [
        if ((trimmed.startsWith("{") && trimmed.endsWith("}"))
                || (trimmed.startsWith("[") && trimmed.endsWith("]"))) {
            return "JSON";
        }

        // XML/HTML: starts with < and has closing tags
        if (trimmed.startsWith("<")) {
            if (trimmed.toLowerCase().contains("<html") || trimmed.toLowerCase().contains("<!doctype")) {
                return "HTML";
            }
            if (trimmed.contains("</") || trimmed.contains("/>")) {
                return "XML";
            }
        }

        // CSV: multiple lines with consistent comma/tab separators
        String[] lines = trimmed.split("\n", 10);
        if (lines.length >= 2) {
            long commaCount0 = lines[0].chars().filter(c -> c == ',').count();
            long commaCount1 = lines[1].chars().filter(c -> c == ',').count();
            if (commaCount0 >= 2 && commaCount0 == commaCount1) {
                return "CSV";
            }
            long tabCount0 = lines[0].chars().filter(c -> c == '\t').count();
            long tabCount1 = lines[1].chars().filter(c -> c == '\t').count();
            if (tabCount0 >= 2 && tabCount0 == tabCount1) {
                return "TSV";
            }
        }

        // Markdown: starts with # heading or has multiple ## headings
        if (trimmed.startsWith("# ") || trimmed.startsWith("## ")
                || (trimmed.contains("\n# ") || trimmed.contains("\n## "))) {
            return "Markdown";
        }

        // YAML: starts with --- or has key: value patterns
        if (trimmed.startsWith("---") || (lines.length >= 2
                && lines[0].matches("^\\w[\\w\\s]*:.*") && lines[1].matches("^\\w[\\w\\s]*:.*"))) {
            return "YAML";
        }

        return "plain text";
    }

    private String getExtension(String path) {
        int dot = path.lastIndexOf('.');
        return dot >= 0 ? path.substring(dot + 1) : "";
    }

    private String fileName(String path) {
        int sep = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return sep >= 0 ? path.substring(sep + 1) : path;
    }
}
