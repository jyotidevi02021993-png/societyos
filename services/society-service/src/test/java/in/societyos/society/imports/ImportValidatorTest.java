package in.societyos.society.imports;

import static org.assertj.core.api.Assertions.assertThat;

import in.societyos.society.imports.application.ImportPlan;
import in.societyos.society.imports.application.ImportValidator;
import in.societyos.society.imports.domain.ImportReport.RowError;
import in.societyos.society.imports.domain.ImportSheets;
import in.societyos.society.imports.domain.ImportSheets.Row;
import in.societyos.society.imports.infrastructure.ExcelWorkbooks;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ImportValidatorTest {

  static final ImportValidator.Existing EMPTY = new ImportValidator.Existing(Map.of(), Set.of());

  static Row row(int n, String... cells) {
    return new Row(n, List.of(cells));
  }

  @Test
  void validWorkbookPlansTowersFlatsAndResidentsWithHoldersFirst() {
    ImportSheets sheets = new ImportSheets(
        List.of(row(2, "Tower A", "A", "14")),
        List.of(row(2, "a", "1203", "12", "1450", "3BHK"), row(3, "A", "101", "1", "", "")),
        List.of(
            row(2, "A-1203", "Ravi", "9876543211", "FAMILY", "", ""),
            row(3, "a-1203", "Asha", "+919876543210", "owner", "Y", "2024-04-01")));
    ImportPlan plan = ImportValidator.validate(sheets, EMPTY);

    assertThat(plan.errors()).isEmpty();
    assertThat(plan.towers()).hasSize(1);
    assertThat(plan.flats()).extracting(ImportPlan.NewFlat::label).containsExactly("A-1203", "A-101");
    assertThat(plan.residents()).extracting(ImportPlan.NewResident::kind).containsExactly("OWNER", "FAMILY");
    assertThat(plan.residents().getFirst().primary()).isTrue();
    assertThat(plan.residents().getLast().phone()).isEqualTo("+919876543211");
  }

  @Test
  void existingTowersAndFlatsAreSkippedSoAFileCanBeRerun() {
    var existing = new ImportValidator.Existing(Map.of("A", 14), Set.of("A-1203"));
    ImportPlan plan = ImportValidator.validate(new ImportSheets(
        List.of(row(2, "Tower A", "A", "14")),
        List.of(row(2, "A", "1203", "12", "", ""), row(3, "A", "1204", "12", "", "")),
        List.of()), existing);

    assertThat(plan.errors()).isEmpty();
    assertThat(plan.towersSkipped()).isEqualTo(1);
    assertThat(plan.flatsSkipped()).isEqualTo(1);
    assertThat(plan.flats()).extracting(ImportPlan.NewFlat::number).containsExactly("1204");
  }

  @Test
  void everyProblemIsReportedWithSheetRowAndColumn() {
    ImportSheets sheets = new ImportSheets(
        List.of(row(2, "Tower A", "A", "abc"), row(3, "Tower B", "B", "5"), row(4, "Again", "b", "5")),
        List.of(row(2, "Z", "1", "1", "", ""), row(3, "B", "601", "6", "", ""), row(4, "B", "101", "1", "0", "")),
        List.of(row(2, "B-999", "", "123", "LANDLORD", "maybe", "01/04/2024")));
    List<RowError> errors = ImportValidator.validate(sheets, EMPTY).errors();

    assertThat(errors).extracting(e -> e.sheet() + ":" + e.row() + ":" + e.column()).contains(
        "Towers:2:Floors", "Towers:4:Code",
        "Flats:2:Tower Code", "Flats:3:Floor", "Flats:4:Area Sqft",
        "Residents:2:Name", "Residents:2:Phone", "Residents:2:Kind", "Residents:2:Primary",
        "Residents:2:From Date", "Residents:2:Flat Label");
    assertThat(errors.getFirst().sheet()).isEqualTo("Towers");
  }

  @Test
  void templateReadsBackAsItsOwnExampleRows() {
    ExcelWorkbooks workbooks = new ExcelWorkbooks();
    ImportSheets sheets = workbooks.read(workbooks.template());
    assertThat(sheets.towers()).singleElement().satisfies(r -> assertThat(r.get(1)).isEqualTo("A"));
    assertThat(sheets.flats()).singleElement().satisfies(r -> assertThat(r.get(1)).isEqualTo("1203"));
    assertThat(sheets.residents()).singleElement().satisfies(r -> assertThat(r.get(0)).isEqualTo("A-1203"));
    assertThat(ImportValidator.validate(sheets, EMPTY).errors()).isEmpty();
  }
}
