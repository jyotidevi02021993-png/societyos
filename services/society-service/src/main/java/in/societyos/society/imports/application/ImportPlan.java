package in.societyos.society.imports.application;

import in.societyos.society.imports.domain.ImportReport.RowError;
import java.time.LocalDate;
import java.util.List;

/** The checked content of a workbook: what to create, what already exists, and what is wrong. */
public record ImportPlan(
    List<NewTower> towers,
    int towersSkipped,
    List<NewFlat> flats,
    int flatsSkipped,
    List<NewResident> residents,
    List<RowError> errors) {

  public record NewTower(int row, String name, String code, int floors) {}

  public record NewFlat(int row, String towerCode, String number, int floor, Integer areaSqft, String flatType) {
    public String label() {
      return towerCode + "-" + number;
    }
  }

  public record NewResident(int row, String flatLabel, String name, String phone, String kind, boolean primary,
      LocalDate fromDate) {}

  public boolean valid() {
    return errors.isEmpty();
  }
}
