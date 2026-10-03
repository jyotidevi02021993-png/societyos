package in.societyos.society;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.societyos.society.common.Phones;
import in.societyos.society.facility.domain.BookingRules;
import in.societyos.society.household.domain.Vehicle;
import in.societyos.society.parking.domain.ParkingSlot;
import in.societyos.society.platform.core.error.ProblemException;
import in.societyos.society.society.domain.Flat;
import in.societyos.society.society.domain.SocietySettings;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DomainRulesTest {

  @Test
  void phonesAreNormalisedToE164() {
    assertThat(Phones.normalize("98765 43210")).isEqualTo("+919876543210");
    assertThat(Phones.normalize("09876543210")).isEqualTo("+919876543210");
    assertThat(Phones.normalize("919876543210")).isEqualTo("+919876543210");
    assertThat(Phones.normalize("+91-98765-43210")).isEqualTo("+919876543210");
    assertThat(Phones.normalize("+14155550100")).isEqualTo("+14155550100");
    assertThatThrownBy(() -> Phones.normalize("12345")).isInstanceOf(ProblemException.class);
    assertThatThrownBy(() -> Phones.normalize("5876543210")).isInstanceOf(ProblemException.class);
    assertThatThrownBy(() -> Phones.normalize(null)).isInstanceOf(ProblemException.class);
  }

  @Test
  void registrationNumbersAreNormalised() {
    assertThat(Vehicle.normalizeRegNo("dl 3c-ab 1234")).isEqualTo("DL3CAB1234");
    assertThatThrownBy(() -> Vehicle.normalizeRegNo("A1")).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void flatOccupancyLeavesRenovationAlone() {
    Flat flat = new Flat(UUID.randomUUID(), "1203", "A-1203", 12, 1450, "3BHK");
    assertThat(flat.getStatus()).isEqualTo("VACANT");
    assertThat(flat.occupancyChanged(true)).isTrue();
    assertThat(flat.getStatus()).isEqualTo("OCCUPIED");
    assertThat(flat.occupancyChanged(true)).isFalse();

    flat.update(12, 1450, "3BHK", "UNDER_RENOVATION");
    assertThat(flat.occupancyChanged(false)).isFalse();
    assertThat(flat.getStatus()).isEqualTo("UNDER_RENOVATION");
    assertThatThrownBy(() -> flat.update(12, null, null, "SOLD")).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void bookingRulesFillDefaultsAndCheckRanges() {
    assertThat(new BookingRules(null, null, null).validated()).isEqualTo(BookingRules.defaults());
    assertThat(new BookingRules(30, 7, 2).validated().slotMinutes()).isEqualTo(30);
    assertThatThrownBy(() -> new BookingRules(10, 7, 2).validated()).isInstanceOf(ProblemException.class);
    assertThatThrownBy(() -> new BookingRules(70, 7, 2).validated()).isInstanceOf(ProblemException.class);
    assertThatThrownBy(() -> new BookingRules(60, 400, 2).validated()).isInstanceOf(ProblemException.class);
  }

  @Test
  void settingsMergeKeepsUnsetFieldsAndFeatureFlags() {
    SocietySettings base = SocietySettings.defaults().merge(
        new SocietySettings(null, null, null, null, null, null, null, Map.of("gate", true)));
    SocietySettings merged = base.merge(
        new SocietySettings(300, null, null, null, 5, null, false, Map.of("billing", false))).validated();
    assertThat(merged.gateApprovalTimeoutSeconds()).isEqualTo(300);
    assertThat(merged.billingDueDay()).isEqualTo(5);
    assertThat(merged.visitorRetentionDays()).isEqualTo(180);
    assertThat(merged.directoryEnabled()).isFalse();
    assertThat(merged.features()).containsEntry("gate", true).containsEntry("billing", false);
    assertThatThrownBy(() -> merged.merge(
        new SocietySettings(null, null, null, null, 31, null, null, null)).validated())
        .isInstanceOf(ProblemException.class);
  }

  @Test
  void visitorSlotsCannotBeAllotted() {
    ParkingSlot visitor = new ParkingSlot("V-01", "VISITOR");
    assertThatThrownBy(() -> visitor.assign(UUID.randomUUID())).isInstanceOf(IllegalStateException.class);
    visitor.assign(null);
    ParkingSlot covered = new ParkingSlot("B1-07", "COVERED");
    UUID flat = UUID.randomUUID();
    covered.assign(flat);
    assertThat(covered.getFlatId()).isEqualTo(flat);
  }
}
