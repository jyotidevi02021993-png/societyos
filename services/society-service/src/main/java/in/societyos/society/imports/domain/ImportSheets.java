package in.societyos.society.imports.domain;

import java.util.List;

/**
 * The rows of an uploaded workbook, as text. Parsing and checking are separate: the reader only
 * turns cells into strings; {@code ImportValidator} decides what they mean.
 */
public record ImportSheets(List<Row> towers, List<Row> flats, List<Row> residents) {

  public static final String TOWERS = "Towers";
  public static final String FLATS = "Flats";
  public static final String RESIDENTS = "Residents";

  public static final List<String> TOWER_COLUMNS = List.of("Name", "Code", "Floors");
  public static final List<String> FLAT_COLUMNS = List.of("Tower Code", "Number", "Floor", "Area Sqft", "Type");
  public static final List<String> RESIDENT_COLUMNS =
      List.of("Flat Label", "Name", "Phone", "Kind", "Primary", "From Date");

  /** One data row: its spreadsheet row number and cell text in column order (blank = ""). */
  public record Row(int number, List<String> cells) {
    public String get(int column) {
      return column < cells.size() && cells.get(column) != null ? cells.get(column).trim() : "";
    }
  }
}
