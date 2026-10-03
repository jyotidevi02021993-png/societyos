package in.societyos.society.imports.domain;

import java.util.List;

/**
 * What an import did (or, for a dry run or failed validation, would do). Row numbers are the
 * spreadsheet's own (header = row 1).
 */
public record ImportReport(Counts towers, Counts flats, Counts residents, List<RowError> errors) {

  /** created = new rows (or would-be new rows), skipped = already there, failed = rejected. */
  public record Counts(int created, int skipped, int failed) {
    public static final Counts ZERO = new Counts(0, 0, 0);
  }

  public record RowError(String sheet, int row, String column, String message) {}

  public static ImportReport failure(String message) {
    return new ImportReport(Counts.ZERO, Counts.ZERO, Counts.ZERO, List.of(new RowError(null, 0, null, message)));
  }
}
