package in.societyos.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.societyos.security.attendance.domain.StaffAttendance;
import in.societyos.security.common.Phones;
import in.societyos.security.common.RuleViolation;
import in.societyos.security.directory.application.SocietyEventData.SettingsUpdated;
import in.societyos.security.directory.domain.FlatVehicle;
import in.societyos.security.directory.domain.SocietySettings;
import in.societyos.security.entry.domain.EntryEvents;
import in.societyos.security.entry.domain.EntryLog;
import in.societyos.security.entry.domain.EntryLog.Arrival;
import in.societyos.security.entry.domain.EntryLog.Status;
import in.societyos.security.gatepass.domain.GatePass;
import in.societyos.security.gatepass.domain.GatePass.Limits;
import in.societyos.security.gatepass.domain.GatePass.PassRejectedException;
import in.societyos.security.gatepass.domain.GatePass.Rejection;
import in.societyos.security.incident.domain.GateIncident;
import in.societyos.security.platform.core.UuidV7;
import in.societyos.security.platform.core.error.ProblemException;
import in.societyos.security.shift.domain.GuardShift;
import in.societyos.security.sos.domain.SosAlert;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class DomainRulesTest {

  static final Instant NOW = Instant.parse("2026-09-29T10:00:00Z");
  static final Limits LIMITS = new Limits(Duration.ofDays(31), 50);
  static final UUID FLAT = UuidV7.next();

  static GatePass pass(Instant from, Instant to, Integer maxUses) {
    return GatePass.issue(FLAT, "GUEST", "Ravi", from, to, maxUses, NOW, LIMITS, "123456", "qr-token");
  }

  static Arrival arrival(String purpose) {
    return new Arrival(FLAT, "A-1203", null, UuidV7.next(), "Ramesh", purpose, "Swiggy", null, null, UuidV7.next());
  }

  @Nested
  class GatePasses {

    @Test
    void validWindowAndUsesAdmitUntilExhausted() {
      GatePass p = pass(NOW, NOW.plus(Duration.ofHours(4)), 2);
      assertThat(p.rejectionAt(NOW.plusSeconds(60))).isEmpty();
      p.use(NOW.plusSeconds(60));
      assertThat(p.usesLeft()).isEqualTo(1);
      assertThat(p.getStatus()).isEqualTo("ACTIVE");
      p.use(NOW.plusSeconds(120));
      assertThat(p.getStatus()).isEqualTo("EXHAUSTED");
      assertThatThrownBy(() -> p.use(NOW.plusSeconds(180)))
          .isInstanceOfSatisfying(PassRejectedException.class, e -> assertThat(e.rejection()).isEqualTo(Rejection.EXHAUSTED));
    }

    @Test
    void defaultsToOneUseStartingNow() {
      GatePass p = pass(null, NOW.plus(Duration.ofHours(1)), null);
      assertThat(p.getValidFrom()).isEqualTo(NOW);
      assertThat(p.getMaxUses()).isEqualTo(1);
    }

    @Test
    void outsideTheWindowIsRejected() {
      GatePass p = pass(NOW.plus(Duration.ofHours(2)), NOW.plus(Duration.ofHours(4)), 1);
      assertThat(p.rejectionAt(NOW)).contains(Rejection.NOT_YET_VALID);
      assertThat(p.rejectionAt(NOW.plus(Duration.ofHours(2)).minus(GatePass.EARLY_GRACE))).isEmpty();
      assertThat(p.rejectionAt(NOW.plus(Duration.ofHours(4)))).contains(Rejection.EXPIRED);
    }

    @Test
    void cancelledAndExpiredPassesCannotBeUsed() {
      GatePass cancelled = pass(NOW, NOW.plus(Duration.ofHours(1)), 3);
      cancelled.cancel();
      assertThat(cancelled.rejectionAt(NOW)).contains(Rejection.CANCELLED);
      assertThatThrownBy(cancelled::cancel).isInstanceOf(IllegalStateException.class);

      GatePass old = pass(NOW, NOW.plus(Duration.ofHours(1)), 3);
      assertThat(old.expireIfPast(NOW)).isFalse();
      assertThat(old.expireIfPast(NOW.plus(Duration.ofHours(1)))).isTrue();
      assertThat(old.getStatus()).isEqualTo("EXPIRED");
    }

    @Test
    void issueRulesAreEnforced() {
      Instant later = NOW.plus(Duration.ofHours(1));
      assertThatThrownBy(() -> pass(NOW, NOW, 1)).hasMessageContaining("validTo");
      assertThatThrownBy(() -> pass(NOW.minus(Duration.ofHours(1)), later, 1)).hasMessageContaining("past");
      assertThatThrownBy(() -> pass(NOW, NOW.plus(Duration.ofDays(40)), 1)).hasMessageContaining("31 days");
      assertThatThrownBy(() -> pass(NOW, later, 0)).hasMessageContaining("maxUses");
      assertThatThrownBy(() -> pass(NOW, later, 51)).hasMessageContaining("maxUses");
      assertThatThrownBy(() -> GatePass.issue(FLAT, "PARTY", null, NOW, later, 1, NOW, LIMITS, "123456", "q"))
          .hasMessageContaining("kind");
      assertThatThrownBy(() -> GatePass.issue(FLAT, "GUEST", null, NOW, later, 1, NOW, LIMITS, "12ab56", "q"))
          .hasMessageContaining("6 digits");
    }
  }

  @Nested
  class Entries {

    @Test
    void requestThenApproveThenInThenOut() {
      EntryLog e = EntryLog.request(arrival("DELIVERY"), NOW, Duration.ofSeconds(90));
      assertThat(e.status()).isEqualTo(Status.REQUESTED);
      assertThat(e.getExpiresAt()).isEqualTo(NOW.plusSeconds(90));
      UUID resident = UuidV7.next();
      e.decide(true, resident, NOW.plusSeconds(30));
      assertThat(e.status()).isEqualTo(Status.APPROVED);
      assertThat(e.getDecidedBy()).isEqualTo(resident);
      e.checkIn(NOW.plusSeconds(40), null);
      e.checkOut(NOW.plusSeconds(600));
      assertThat(e.status()).isEqualTo(Status.OUT);
    }

    @Test
    void denyIsFinal() {
      EntryLog e = EntryLog.request(arrival("GUEST"), NOW, Duration.ofSeconds(90));
      e.decide(false, UuidV7.next(), NOW.plusSeconds(10));
      assertThat(e.status()).isEqualTo(Status.DENIED);
      assertThatThrownBy(() -> e.decide(true, UuidV7.next(), NOW.plusSeconds(20)))
          .isInstanceOfSatisfying(ProblemException.class, p -> assertThat(p.code()).isEqualTo("ENTRY_ALREADY_DECIDED"));
      assertThatThrownBy(() -> e.checkIn(NOW.plusSeconds(30), null))
          .isInstanceOfSatisfying(ProblemException.class, p -> assertThat(p.code()).isEqualTo("ENTRY_NOT_APPROVED"));
    }

    @Test
    void timeoutExpiresTheRequestAndBlocksLateDecisions() {
      EntryLog e = EntryLog.request(arrival("CAB"), NOW, Duration.ofSeconds(60));
      assertThat(e.expireIfDue(NOW.plusSeconds(59))).isFalse();
      assertThatThrownBy(() -> e.decide(true, UuidV7.next(), NOW.plusSeconds(60)))
          .isInstanceOfSatisfying(RuleViolation.class, p -> assertThat(p.code()).isEqualTo("ENTRY_EXPIRED"));
      assertThat(e.expireIfDue(NOW.plusSeconds(60))).isTrue();
      assertThat(e.status()).isEqualTo(Status.EXPIRED);
      assertThat(e.expireIfDue(NOW.plusSeconds(61))).isFalse();
    }

    @Test
    void staffIsNotAWalkInPurpose() {
      assertThatThrownBy(() -> EntryLog.request(arrival("STAFF"), NOW, Duration.ofSeconds(60)))
          .isInstanceOfSatisfying(RuleViolation.class, p -> assertThat(p.code()).isEqualTo("INVALID_PURPOSE"));
    }

    @Test
    void passAdmissionIsInsideStraightAway() {
      UUID passId = UuidV7.next();
      EntryLog e = EntryLog.admitted(arrival("GUEST"), NOW, "PASS", passId, null);
      assertThat(e.status()).isEqualTo(Status.IN);
      assertThat(e.getInAt()).isEqualTo(NOW);
      assertThat(e.getPassId()).isEqualTo(passId);
      assertThatThrownBy(() -> e.decide(true, UuidV7.next(), NOW)).isInstanceOf(RuleViolation.class);
    }

    @Test
    void requestedEventCarriesNamesButNoPhone() {
      EntryLog e = EntryLog.request(arrival("DELIVERY"), NOW, Duration.ofSeconds(60));
      var event = EntryEvents.requested(e, List.of(UuidV7.next()));
      assertThat(event.type()).isEqualTo("security.entry.requested");
      assertThat(event.context()).isEqualTo("security");
      assertThat(event.toString()).doesNotContain("+91").contains("Ramesh");
      assertThat(EntryEvents.expired(e).type()).isEqualTo("security.entry.expired");
    }
  }

  @Nested
  class Settings {

    @Test
    void societySettingsAreClampedAndParsed() {
      SocietySettings s = new SocietySettings(UuidV7.next(), 120, 180);
      var update = new SettingsUpdated(UuidV7.next(), Map.of("gateApprovalTimeoutSeconds", 5, "visitorRetentionDays", "90"));
      s.update(update.intSetting("gateApprovalTimeoutSeconds"), update.intSetting("visitorRetentionDays"));
      assertThat(s.approvalTimeout()).isEqualTo(Duration.ofSeconds(10));
      assertThat(s.getVisitorRetentionDays()).isEqualTo(90);
      s.update(null, null);
      assertThat(s.getVisitorRetentionDays()).isEqualTo(90);
    }

    @Test
    void phonesAndPlatesNormalise() {
      assertThat(Phones.normalise("98765 43210")).isEqualTo("+919876543210");
      assertThat(Phones.normalise("+91-98765-43210")).isEqualTo("+919876543210");
      assertThat(Phones.normalise("09876543210")).isEqualTo("+919876543210");
      assertThat(Phones.normalise(" ")).isNull();
      assertThatThrownBy(() -> Phones.normalise("12345")).isInstanceOf(ProblemException.class);
      assertThat(FlatVehicle.normaliseRegNo("ka 01-ab 1234")).isEqualTo("KA01AB1234");
    }
  }

  @Nested
  class Security {

    @Test
    void sosLifecycle() {
      SosAlert s = new SosAlert(UuidV7.next(), FLAT, "fire", null, NOW);
      assertThat(s.getKind()).isEqualTo("FIRE");
      assertThat(s.state()).isEqualTo("OPEN");
      UUID guard = UuidV7.next();
      s.acknowledge(guard, NOW.plusSeconds(5));
      assertThat(s.state()).isEqualTo("ACKNOWLEDGED");
      s.resolve(guard, NOW.plusSeconds(60));
      assertThat(s.state()).isEqualTo("RESOLVED");
      assertThatThrownBy(() -> s.resolve(guard, NOW)).isInstanceOf(RuleViolation.class);
      assertThatThrownBy(() -> new SosAlert(guard, null, "PARTY", null, NOW)).isInstanceOf(RuleViolation.class);
    }

    @Test
    void seriousIncidentsAreHighOrCritical() {
      UUID by = UuidV7.next();
      assertThat(new GateIncident("theft", "high", null, null, null, null, by, NOW).isSerious()).isTrue();
      assertThat(new GateIncident("theft", "CRITICAL", null, null, null, null, by, NOW).isSerious()).isTrue();
      assertThat(new GateIncident("noise", null, null, null, null, null, by, NOW).isSerious()).isFalse();
      assertThatThrownBy(() -> new GateIncident("x", "SEVERE", null, null, null, null, by, NOW))
          .isInstanceOf(RuleViolation.class);
    }

    @Test
    void guardShiftWindowAndOwnership() {
      UUID guard = UuidV7.next();
      GuardShift shift = new GuardShift(guard, null, NOW, NOW.plus(Duration.ofHours(8)));
      assertThatThrownBy(() -> shift.checkIn(guard, NOW.minus(Duration.ofHours(1)))).isInstanceOf(RuleViolation.class);
      assertThatThrownBy(() -> shift.checkIn(UuidV7.next(), NOW)).isInstanceOf(RuleViolation.class);
      shift.checkIn(guard, NOW.minus(Duration.ofMinutes(10)));
      shift.checkOut(guard, NOW.plus(Duration.ofHours(8)));
      assertThatThrownBy(() -> new GuardShift(guard, null, NOW, NOW.plus(Duration.ofHours(25))))
          .isInstanceOf(RuleViolation.class);
    }

    @Test
    void blockedStaffAreTurnedAway() {
      assertThatThrownBy(() -> StaffAttendance.requireAllowed("BLOCKED"))
          .isInstanceOfSatisfying(RuleViolation.class, p -> assertThat(p.code()).isEqualTo("STAFF_BLOCKED"));
      StaffAttendance.requireAllowed("ACTIVE");
      StaffAttendance a = new StaffAttendance(UuidV7.next(), null, NOW, null, null);
      a.checkOut(NOW.plus(Duration.ofHours(3)));
      assertThat(a.duration(NOW)).isEqualTo(Duration.ofHours(3));
      assertThatThrownBy(() -> a.checkOut(NOW)).isInstanceOf(RuleViolation.class);
    }
  }
}
