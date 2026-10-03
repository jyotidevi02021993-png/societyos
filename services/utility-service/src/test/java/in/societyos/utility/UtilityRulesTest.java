package in.societyos.utility;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.societyos.utility.checklist.domain.ChecklistRules;
import in.societyos.utility.checklist.domain.ChecklistRules.Answer;
import in.societyos.utility.checklist.domain.ChecklistRules.Item;
import in.societyos.utility.checklist.domain.ChecklistRules.ItemType;
import in.societyos.utility.checklist.domain.ChecklistRules.Result;
import in.societyos.utility.meter.domain.Meter;
import in.societyos.utility.meter.domain.ReadingRules;
import in.societyos.utility.meter.domain.ReadingRules.Mode;
import in.societyos.utility.meter.domain.ReadingRules.Reason;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class UtilityRulesTest {

  static BigDecimal d(String s) {
    return s == null || s.isBlank() ? null : new BigDecimal(s.trim());
  }

  @ParameterizedTest(name = "instant {0} in [{1},{2}] -> {3}")
  @CsvSource(nullValues = "-", value = {
      "6.5, 6.5, 8.5, -",
      "8.5, 6.5, 8.5, -",
      "6.4, 6.5, 8.5, BELOW_MIN",
      "9.1, 6.5, 8.5, ABOVE_MAX",
      "3,   -,   8.5, -",
      "99,  10,  -,   -",
      "5,   10,  -,   BELOW_MIN"})
  void instantThresholds(String value, String min, String max, String reason) {
    ReadingRules.Evaluation e = ReadingRules.evaluate(Mode.INSTANT, d(value), d("7"), d(min), d(max), null);
    assertThat(e.delta()).isNull();
    assertThat(e.reason()).isEqualTo(reason == null ? null : Reason.valueOf(reason));
  }

  @Test
  void cumulativeMetersCompareConsumption() {
    // first reading: nothing to compare
    assertThat(ReadingRules.evaluate(Mode.CUMULATIVE, d("1000"), null, d("50"), d("200"), d("400")).anomaly()).isFalse();
    // normal daily consumption: 120 KL
    ReadingRules.Evaluation ok = ReadingRules.evaluate(Mode.CUMULATIVE, d("1120"), d("1000"), d("50"), d("200"), d("400"));
    assertThat(ok.delta()).isEqualByComparingTo("120");
    assertThat(ok.anomaly()).isFalse();
    // too little (leak in the meter line or pump off) and too much
    assertThat(ReadingRules.evaluate(Mode.CUMULATIVE, d("1020"), d("1000"), d("50"), d("200"), null).reason())
        .isEqualTo(Reason.BELOW_MIN);
    assertThat(ReadingRules.evaluate(Mode.CUMULATIVE, d("1250"), d("1000"), d("50"), d("200"), null).reason())
        .isEqualTo(Reason.ABOVE_MAX);
    // spike beats the max band
    assertThat(ReadingRules.evaluate(Mode.CUMULATIVE, d("1500"), d("1000"), d("50"), d("200"), d("400")).reason())
        .isEqualTo(Reason.CONSUMPTION_SPIKE);
    // the meter went backwards
    ReadingRules.Evaluation back = ReadingRules.evaluate(Mode.CUMULATIVE, d("990"), d("1000"), null, null, null);
    assertThat(back.reason()).isEqualTo(Reason.METER_ROLLBACK);
    assertThat(back.delta()).isEqualByComparingTo("-10");
    assertThatThrownBy(() -> ReadingRules.evaluate(Mode.CUMULATIVE, d("-1"), null, null, null, null))
        .hasMessageContaining("below zero");
  }

  @Test
  void meterDetailsAreValidated() {
    assertThatThrownBy(() -> new Meter.Details("pH", "STP", null, null, "PH", "pH", Mode.INSTANT, d("9"), d("6"), null))
        .hasMessageContaining("expectedMin");
    assertThatThrownBy(() -> new Meter.Details("Level", "TANK", null, null, "LEVEL", "%", Mode.INSTANT, null, null, d("10")))
        .hasMessageContaining("maxDelta");
    assertThatThrownBy(() -> new Meter.Details("X", "BOILER", null, null, "X", "u", Mode.INSTANT, null, null, null))
        .hasMessageContaining("system");
    assertThat(new Meter.Details("DG hours", "DG", null, null, "running_hours", "h", Mode.CUMULATIVE, null, null, d("24"))
        .metric()).isEqualTo("RUNNING_HOURS");
  }

  @Test
  void checklistNumbersOutsideRangeFail() {
    List<Item> items = ChecklistRules.validateItems(List.of(
        new Item("ph", "Outlet pH", ItemType.NUMBER, true, d("6.5"), d("8.5"), "pH"),
        new Item("blower", "Blower running", ItemType.CHECK, true, null, null, null),
        new Item(null, "Remarks", ItemType.TEXT, false, null, null, null)));
    assertThat(items).extracting(Item::code).containsExactly("PH", "BLOWER", "ITEM_3");

    ChecklistRules.Evaluation e = ChecklistRules.evaluate(items, List.of(
        new Answer("PH", Result.OK, d("9.2"), null, null, null),
        new Answer("blower", Result.OK, null, null, null, null)));
    assertThat(e.failed()).isEqualTo(1);
    assertThat(e.ok()).isEqualTo(1);
    assertThat(e.failures().getFirst().item().code()).isEqualTo("PH");
    assertThat(e.failures().getFirst().note()).contains("9.2").contains("6.5..8.5");

    ChecklistRules.Evaluation na = ChecklistRules.evaluate(items, List.of(
        new Answer("PH", Result.NA, null, null, "Plant shut for cleaning", null),
        new Answer("BLOWER", Result.NOT_OK, null, null, "Tripped", null),
        new Answer("ITEM_3", null, null, "All fine otherwise", null, null)));
    assertThat(na.na()).isEqualTo(1);
    assertThat(na.failed()).isEqualTo(1);
    assertThat(na.ok()).isEqualTo(1);
  }

  @Test
  void checklistAnswersAreChecked() {
    List<Item> items = ChecklistRules.validateItems(List.of(
        new Item("ph", "Outlet pH", ItemType.NUMBER, true, d("6.5"), d("8.5"), "pH"),
        new Item("blower", "Blower running", ItemType.CHECK, true, null, null, null)));
    assertThatThrownBy(() -> ChecklistRules.evaluate(items, List.of(new Answer("PH", null, d("7"), null, null, null))))
        .hasMessageContaining("Required item BLOWER");
    assertThatThrownBy(() -> ChecklistRules.evaluate(items, List.of(
        new Answer("PH", Result.OK, null, null, null, null), new Answer("BLOWER", Result.OK, null, null, null, null))))
        .hasMessageContaining("needs a value");
    assertThatThrownBy(() -> ChecklistRules.evaluate(items, List.of(new Answer("NOPE", Result.OK, null, null, null, null))))
        .hasMessageContaining("Unknown item");
    assertThatThrownBy(() -> ChecklistRules.validateItems(List.of()))
        .hasMessageContaining("at least one item");
    assertThatThrownBy(() -> ChecklistRules.validateItems(List.of(new Item("a", "A", ItemType.CHECK, true, d("1"), null, null))))
        .hasMessageContaining("NUMBER items only");
  }
}
