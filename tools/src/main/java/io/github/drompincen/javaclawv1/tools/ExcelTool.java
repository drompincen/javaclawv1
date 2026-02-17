package io.github.drompincen.javaclawv1.tools;

import io.github.drompincen.javaclawv1.protocol.api.ToolRiskProfile;
import io.github.drompincen.javaclawv1.runtime.tools.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.file.Path;
import java.util.*;

public class ExcelTool implements Tool {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override public String name() { return "excel"; }

    @Override public String description() {
        return "Read and write Excel files (.xlsx, .xls). Operations: 'read' to extract data from sheets, " +
               "'write' to create/update Excel files, 'list_sheets' to list sheet names. " +
               "Useful for Jira exports, sprint data, team capacity tracking, and report generation.";
    }

    @Override public JsonNode inputSchema() {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");

        props.putObject("operation").put("type", "string")
                .put("description", "Operation: 'read', 'write', or 'list_sheets'");

        props.putObject("file_path").put("type", "string")
                .put("description", "Path to the Excel file (relative to working directory or absolute)");

        props.putObject("sheet_name").put("type", "string")
                .put("description", "Sheet name to read/write. Default: first sheet (read) or 'Sheet1' (write)");

        props.putObject("range").put("type", "string")
                .put("description", "Cell range to read (e.g., 'A1:D10'). Default: all data");

        props.putObject("max_rows").put("type", "integer")
                .put("description", "Maximum rows to read (default 500, to prevent huge outputs)");

        ObjectNode dataNode = props.putObject("data");
        dataNode.put("type", "array");
        dataNode.put("description", "For 'write': array of arrays (rows of cell values). First row = headers.");
        ObjectNode itemsNode = dataNode.putObject("items");
        itemsNode.put("type", "array");
        itemsNode.putObject("items").put("type", "string");

        schema.putArray("required").add("operation").add("file_path");
        return schema;
    }

    @Override public JsonNode outputSchema() { return MAPPER.createObjectNode().put("type", "object"); }
    @Override public Set<ToolRiskProfile> riskProfiles() { return Set.of(ToolRiskProfile.READ_ONLY, ToolRiskProfile.WRITE_FILES); }

    @Override
    public ToolResult execute(ToolContext ctx, JsonNode input, ToolStream stream) {
        String operation = input.path("operation").asText("read");
        String filePath = input.path("file_path").asText();
        Path resolvedPath = ctx.workingDirectory().resolve(filePath);

        return switch (operation) {
            case "read" -> handleRead(resolvedPath, input, stream);
            case "write" -> handleWrite(resolvedPath, input, stream);
            case "list_sheets" -> handleListSheets(resolvedPath, stream);
            default -> ToolResult.failure("Unknown operation: " + operation);
        };
    }

    private ToolResult handleRead(Path filePath, JsonNode input, ToolStream stream) {
        try (FileInputStream fis = new FileInputStream(filePath.toFile());
             Workbook workbook = WorkbookFactory.create(fis)) {

            String sheetName = input.path("sheet_name").asText(null);
            int maxRows = input.path("max_rows").asInt(500);

            Sheet sheet = sheetName != null ? workbook.getSheet(sheetName) : workbook.getSheetAt(0);
            if (sheet == null) return ToolResult.failure("Sheet not found: " + sheetName);

            stream.progress(10, "Reading sheet: " + sheet.getSheetName());

            ArrayNode rows = MAPPER.createArrayNode();
            int rowCount = 0;

            for (Row row : sheet) {
                if (rowCount >= maxRows) break;
                ArrayNode cells = rows.addArray();
                for (int c = 0; c < row.getLastCellNum(); c++) {
                    Cell cell = row.getCell(c, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK);
                    cells.add(getCellValue(cell));
                }
                rowCount++;
            }

            stream.progress(100, "Read " + rowCount + " rows from " + sheet.getSheetName());

            ObjectNode result = MAPPER.createObjectNode();
            result.put("sheet", sheet.getSheetName());
            result.put("rowCount", rowCount);
            result.put("file", filePath.toString());
            result.set("data", rows);
            return ToolResult.success(result);

        } catch (Exception e) {
            return ToolResult.failure("Failed to read Excel file: " + e.getMessage());
        }
    }

    private ToolResult handleWrite(Path filePath, JsonNode input, ToolStream stream) {
        try {
            String sheetName = input.path("sheet_name").asText("Sheet1");
            JsonNode data = input.get("data");
            if (data == null || !data.isArray()) return ToolResult.failure("'data' array is required for write");

            boolean isXlsx = filePath.toString().toLowerCase().endsWith(".xlsx") || !filePath.toString().toLowerCase().endsWith(".xls");

            Workbook workbook;
            // Try to open existing file, or create new
            if (filePath.toFile().exists()) {
                try (FileInputStream fis = new FileInputStream(filePath.toFile())) {
                    workbook = WorkbookFactory.create(fis);
                }
            } else {
                workbook = isXlsx ? new XSSFWorkbook() : new HSSFWorkbook();
            }

            Sheet sheet = workbook.getSheet(sheetName);
            if (sheet == null) sheet = workbook.createSheet(sheetName);

            stream.progress(30, "Writing data to " + sheetName);

            int rowIdx = 0;
            for (JsonNode rowData : data) {
                Row row = sheet.createRow(rowIdx++);
                int cellIdx = 0;
                for (JsonNode cellData : rowData) {
                    Cell cell = row.createCell(cellIdx++);
                    if (cellData.isNumber()) {
                        cell.setCellValue(cellData.doubleValue());
                    } else {
                        cell.setCellValue(cellData.asText());
                    }
                }
            }

            // Auto-size columns
            if (sheet.getRow(0) != null) {
                for (int c = 0; c < sheet.getRow(0).getLastCellNum(); c++) {
                    sheet.autoSizeColumn(c);
                }
            }

            try (FileOutputStream fos = new FileOutputStream(filePath.toFile())) {
                workbook.write(fos);
            }
            workbook.close();

            stream.progress(100, "Wrote " + rowIdx + " rows to " + filePath.getFileName());

            ObjectNode result = MAPPER.createObjectNode();
            result.put("written", true);
            result.put("file", filePath.toString());
            result.put("sheet", sheetName);
            result.put("rowCount", rowIdx);
            return ToolResult.success(result);

        } catch (Exception e) {
            return ToolResult.failure("Failed to write Excel file: " + e.getMessage());
        }
    }

    private ToolResult handleListSheets(Path filePath, ToolStream stream) {
        try (FileInputStream fis = new FileInputStream(filePath.toFile());
             Workbook workbook = WorkbookFactory.create(fis)) {

            ArrayNode sheets = MAPPER.createArrayNode();
            for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
                ObjectNode sheetInfo = sheets.addObject();
                Sheet sheet = workbook.getSheetAt(i);
                sheetInfo.put("name", sheet.getSheetName());
                sheetInfo.put("rowCount", sheet.getLastRowNum() + 1);
                sheetInfo.put("index", i);
            }

            stream.progress(100, "Found " + sheets.size() + " sheets");

            ObjectNode result = MAPPER.createObjectNode();
            result.put("file", filePath.toString());
            result.set("sheets", sheets);
            return ToolResult.success(result);

        } catch (Exception e) {
            return ToolResult.failure("Failed to list sheets: " + e.getMessage());
        }
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
}
