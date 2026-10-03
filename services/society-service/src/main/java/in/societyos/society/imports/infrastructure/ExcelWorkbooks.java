package in.societyos.society.imports.infrastructure;

import in.societyos.society.imports.domain.ImportSheets;
import in.societyos.society.imports.domain.ImportSheets.Row;
import in.societyos.society.platform.core.error.ProblemException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

/** Reads the onboarding workbook into text rows, and writes the blank template. */
@Component
public class ExcelWorkbooks {

  static final int MAX_ROWS_PER_SHEET = 5_000;

  public ImportSheets read(byte[] xlsx) {
    try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(xlsx))) {
      Sheet towers = sheet(wb, ImportSheets.TOWERS);
      Sheet flats = sheet(wb, ImportSheets.FLATS);
      Sheet residents = sheet(wb, ImportSheets.RESIDENTS);
      if (towers == null && flats == null && residents == null) {
        throw ProblemException.badRequest("IMPORT_NO_SHEETS",
            "The workbook needs at least one sheet named Towers, Flats or Residents");
      }
      return new ImportSheets(
          rows(towers, ImportSheets.TOWER_COLUMNS),
          rows(flats, ImportSheets.FLAT_COLUMNS),
          rows(residents, ImportSheets.RESIDENT_COLUMNS));
    } catch (IOException | RuntimeException e) {
      if (e instanceof ProblemException p) {
        throw p;
      }
      throw ProblemException.badRequest("IMPORT_UNREADABLE", "The file is not a readable .xlsx workbook");
    }
  }

  public byte[] template() {
    try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      CellStyle bold = wb.createCellStyle();
      Font font = wb.createFont();
      font.setBold(true);
      bold.setFont(font);
      header(wb, ImportSheets.TOWERS, ImportSheets.TOWER_COLUMNS, bold, List.of("Tower A", "A", "14"));
      header(wb, ImportSheets.FLATS, ImportSheets.FLAT_COLUMNS, bold, List.of("A", "1203", "12", "1450", "3BHK"));
      header(wb, ImportSheets.RESIDENTS, ImportSheets.RESIDENT_COLUMNS, bold,
          List.of("A-1203", "Asha Verma", "+919876543210", "OWNER", "Y", "2024-04-01"));
      wb.write(out);
      return out.toByteArray();
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  private static void header(Workbook wb, String name, List<String> columns, CellStyle bold, List<String> example) {
    Sheet sheet = wb.createSheet(name);
    var head = sheet.createRow(0);
    var sample = sheet.createRow(1);
    for (int i = 0; i < columns.size(); i++) {
      Cell c = head.createCell(i);
      c.setCellValue(columns.get(i));
      c.setCellStyle(bold);
      sample.createCell(i, CellType.STRING).setCellValue(example.get(i));
      sheet.setColumnWidth(i, 18 * 256);
    }
  }

  private static Sheet sheet(Workbook wb, String name) {
    for (Sheet s : wb) {
      if (s.getSheetName().trim().equalsIgnoreCase(name)) {
        return s;
      }
    }
    return null;
  }

  /** Maps columns by header text (any order, case-insensitive) and skips fully blank rows. */
  private static List<Row> rows(Sheet sheet, List<String> columns) {
    if (sheet == null) {
      return List.of();
    }
    DataFormatter fmt = new DataFormatter(Locale.ROOT);
    var headRow = sheet.getRow(sheet.getFirstRowNum());
    if (headRow == null) {
      return List.of();
    }
    int[] index = new int[columns.size()];
    java.util.Arrays.fill(index, -1);
    for (Cell c : headRow) {
      String text = fmt.formatCellValue(c).trim();
      for (int i = 0; i < columns.size(); i++) {
        if (columns.get(i).equalsIgnoreCase(text)) {
          index[i] = c.getColumnIndex();
        }
      }
    }
    if (index[0] < 0 || index[1] < 0) {
      throw ProblemException.badRequest("IMPORT_BAD_HEADER",
          "Sheet %s needs the header row: %s".formatted(sheet.getSheetName(), String.join(", ", columns)));
    }
    List<Row> result = new ArrayList<>();
    for (int r = headRow.getRowNum() + 1; r <= sheet.getLastRowNum(); r++) {
      var row = sheet.getRow(r);
      if (row == null) {
        continue;
      }
      List<String> cells = new ArrayList<>(columns.size());
      boolean blank = true;
      for (int i = 0; i < columns.size(); i++) {
        String v = index[i] < 0 ? "" : text(row.getCell(index[i]), fmt);
        blank &= v.isBlank();
        cells.add(v);
      }
      if (blank) {
        continue;
      }
      if (result.size() == MAX_ROWS_PER_SHEET) {
        throw ProblemException.badRequest("IMPORT_TOO_LARGE",
            "Sheet %s has more than %d rows; split the file".formatted(sheet.getSheetName(), MAX_ROWS_PER_SHEET));
      }
      result.add(new Row(r + 1, cells));
    }
    return result;
  }

  /** Dates become yyyy-MM-dd; numbers lose Excel's ".0"; everything else as displayed. */
  private static String text(Cell cell, DataFormatter fmt) {
    if (cell == null) {
      return "";
    }
    CellType type = cell.getCellType() == CellType.FORMULA ? cell.getCachedFormulaResultType() : cell.getCellType();
    if (type == CellType.NUMERIC && DateUtil.isCellDateFormatted(cell)) {
      return cell.getLocalDateTimeCellValue().toLocalDate().format(DateTimeFormatter.ISO_LOCAL_DATE);
    }
    if (type == CellType.NUMERIC) {
      double d = cell.getNumericCellValue();
      if (d == Math.rint(d) && Math.abs(d) < 1e15) {
        return Long.toString((long) d);
      }
    }
    return fmt.formatCellValue(cell).trim();
  }
}
