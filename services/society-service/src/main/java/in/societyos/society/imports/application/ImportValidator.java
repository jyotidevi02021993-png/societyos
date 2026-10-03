package in.societyos.society.imports.application;

import in.societyos.society.common.Phones;
import in.societyos.society.imports.application.ImportPlan.NewFlat;
import in.societyos.society.imports.application.ImportPlan.NewResident;
import in.societyos.society.imports.application.ImportPlan.NewTower;
import in.societyos.society.imports.domain.ImportReport.RowError;
import in.societyos.society.imports.domain.ImportSheets;
import in.societyos.society.imports.domain.ImportSheets.Row;
import in.societyos.society.platform.core.error.ProblemException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Checks every row of a workbook against the file itself and what the society already has.
 * Towers and flats that already exist are skipped (so a file can be re-run); everything else
 * that is wrong is reported with sheet, row and column. Pure: no database access.
 */
public final class ImportValidator {

  /**
   * @param existingTowerFloors existing towers: upper-case code → floor count
   * @param existingFlatLabels existing flat labels, upper case
   */
  public record Existing(Map<String, Integer> existingTowerFloors, Set<String> existingFlatLabels) {}

  private static final Set<String> KINDS = Set.of("OWNER", "TENANT", "FAMILY");

  private ImportValidator() {}

  public static ImportPlan validate(ImportSheets sheets, Existing existing) {
    List<RowError> errors = new ArrayList<>();

    // Towers
    Map<String, Integer> towerFloors = new HashMap<>(existing.existingTowerFloors());
    Map<String, String> towerCodeCase = new HashMap<>();
    List<NewTower> towers = new ArrayList<>();
    int towersSkipped = 0;
    Set<String> codesInFile = new HashSet<>();
    for (Row r : sheets.towers()) {
      String t = ImportSheets.TOWERS;
      String name = r.get(0);
      String code = r.get(1);
      Integer floors = integer(r.get(2), 0, 200, t, r, "Floors", errors, true);
      boolean ok = required(name, t, r, "Name", errors) & required(code, t, r, "Code", errors);
      if (code.length() > 10) {
        errors.add(new RowError(t, r.number(), "Code", "At most 10 characters"));
        ok = false;
      }
      if (!ok || floors == null) {
        continue;
      }
      String key = code.toUpperCase(Locale.ROOT);
      if (!codesInFile.add(key)) {
        errors.add(new RowError(t, r.number(), "Code", "Tower code " + code + " appears twice in the file"));
        continue;
      }
      if (existing.existingTowerFloors().containsKey(key)) {
        towersSkipped++;
        continue;
      }
      towerFloors.put(key, floors);
      towerCodeCase.put(key, code);
      towers.add(new NewTower(r.number(), name, code, floors));
    }

    // Flats
    Set<String> labels = new HashSet<>(existing.existingFlatLabels());
    Set<String> labelsInFile = new HashSet<>();
    List<NewFlat> flats = new ArrayList<>();
    int flatsSkipped = 0;
    for (Row r : sheets.flats()) {
      String s = ImportSheets.FLATS;
      String towerCode = r.get(0);
      String number = r.get(1);
      Integer floor = integer(r.get(2), 0, 200, s, r, "Floor", errors, true);
      Integer area = integer(r.get(3), 1, 1_000_000, s, r, "Area Sqft", errors, false);
      boolean ok = required(towerCode, s, r, "Tower Code", errors) & required(number, s, r, "Number", errors);
      if (!ok || floor == null || (!r.get(3).isBlank() && area == null)) {
        continue;
      }
      String key = towerCode.toUpperCase(Locale.ROOT);
      Integer floors = towerFloors.get(key);
      if (floors == null) {
        errors.add(new RowError(s, r.number(), "Tower Code", "No tower with code " + towerCode));
        continue;
      }
      if (floor > floors) {
        errors.add(new RowError(s, r.number(), "Floor", "Tower " + towerCode + " has only " + floors + " floors"));
        continue;
      }
      String code = towerCodeCase.getOrDefault(key, towerCode);
      String label = (code + "-" + number).toUpperCase(Locale.ROOT);
      if (!labelsInFile.add(label)) {
        errors.add(new RowError(s, r.number(), "Number", "Flat " + code + "-" + number + " appears twice in the file"));
        continue;
      }
      if (existing.existingFlatLabels().contains(label)) {
        flatsSkipped++;
        continue;
      }
      labels.add(label);
      flats.add(new NewFlat(r.number(), code, number, floor, area, r.get(4).isBlank() ? null : r.get(4)));
    }

    // Residents
    List<NewResident> residents = new ArrayList<>();
    Set<String> personInFlat = new HashSet<>();
    for (Row r : sheets.residents()) {
      String s = ImportSheets.RESIDENTS;
      String label = r.get(0);
      String name = r.get(1);
      boolean ok = required(label, s, r, "Flat Label", errors) & required(name, s, r, "Name", errors);
      String phone = null;
      try {
        phone = Phones.normalize(r.get(2));
      } catch (ProblemException e) {
        errors.add(new RowError(s, r.number(), "Phone", "Not a valid mobile number"));
        ok = false;
      }
      String kind = r.get(3).isBlank() ? "OWNER" : r.get(3).toUpperCase(Locale.ROOT);
      if (!KINDS.contains(kind)) {
        errors.add(new RowError(s, r.number(), "Kind", "Use OWNER, TENANT or FAMILY"));
        ok = false;
      }
      Boolean primary = yesNo(r.get(4));
      if (primary == null) {
        errors.add(new RowError(s, r.number(), "Primary", "Use Y or N"));
        ok = false;
      } else if (primary && "FAMILY".equals(kind)) {
        errors.add(new RowError(s, r.number(), "Primary", "Only an owner or tenant can be primary"));
        ok = false;
      }
      LocalDate from = null;
      if (!r.get(5).isBlank()) {
        try {
          from = LocalDate.parse(r.get(5));
        } catch (DateTimeParseException e) {
          errors.add(new RowError(s, r.number(), "From Date", "Use a date like 2024-04-01"));
          ok = false;
        }
      }
      if (!label.isBlank() && !labels.contains(label.toUpperCase(Locale.ROOT))) {
        errors.add(new RowError(s, r.number(), "Flat Label", "No flat " + label));
        ok = false;
      }
      if (ok && !personInFlat.add(label.toUpperCase(Locale.ROOT) + "|" + phone)) {
        errors.add(new RowError(s, r.number(), "Phone", "This person is listed twice for " + label));
        ok = false;
      }
      if (ok) {
        residents.add(new NewResident(r.number(), label.toUpperCase(Locale.ROOT), name, phone, kind, primary, from));
      }
    }
    // Owners and tenants first, so family members always find a holder in their flat.
    residents.sort(Comparator.comparing((NewResident n) -> "FAMILY".equals(n.kind())).thenComparing(NewResident::row));

    errors.sort(Comparator.comparing((RowError e) -> sheetOrder(e.sheet())).thenComparing(RowError::row));
    return new ImportPlan(towers, towersSkipped, flats, flatsSkipped, residents, errors);
  }

  private static int sheetOrder(String sheet) {
    return switch (sheet) {
      case ImportSheets.TOWERS -> 0;
      case ImportSheets.FLATS -> 1;
      default -> 2;
    };
  }

  private static boolean required(String v, String sheet, Row r, String column, List<RowError> errors) {
    if (v.isBlank()) {
      errors.add(new RowError(sheet, r.number(), column, column + " is required"));
      return false;
    }
    return true;
  }

  private static Integer integer(String v, int min, int max, String sheet, Row r, String column,
      List<RowError> errors, boolean required) {
    if (v.isBlank()) {
      if (required) {
        errors.add(new RowError(sheet, r.number(), column, column + " is required"));
      }
      return null;
    }
    try {
      int n = Integer.parseInt(v);
      if (n < min || n > max) {
        errors.add(new RowError(sheet, r.number(), column, "Must be between %d and %d".formatted(min, max)));
        return null;
      }
      return n;
    } catch (NumberFormatException e) {
      errors.add(new RowError(sheet, r.number(), column, "Must be a whole number"));
      return null;
    }
  }

  private static Boolean yesNo(String v) {
    return switch (v.trim().toUpperCase(Locale.ROOT)) {
      case "", "N", "NO", "FALSE", "0" -> false;
      case "Y", "YES", "TRUE", "1" -> true;
      default -> null;
    };
  }
}
