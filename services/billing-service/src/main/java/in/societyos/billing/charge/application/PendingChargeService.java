package in.societyos.billing.charge.application;

import in.societyos.billing.charge.domain.PendingCharge;
import in.societyos.billing.charge.infrastructure.PendingChargeRepository;
import in.societyos.billing.platform.core.error.ProblemException;
import in.societyos.billing.roster.application.RosterService;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** One-off charges that the next bill run adds to a flat's bill. */
@Service
public class PendingChargeService {

  /** Booking data from {@code community.booking.confirmed / .cancelled}. */
  public record Booking(UUID bookingId, UUID facilityId, UUID flatId, java.time.Instant startsAt, long chargePaise) {}

  private final PendingChargeRepository charges;
  private final RosterService roster;

  public PendingChargeService(PendingChargeRepository charges, RosterService roster) {
    this.charges = charges;
    this.roster = roster;
  }

  @Transactional
  public PendingCharge addManual(UUID flatId, String description, long amountPaise, boolean gstApplicable) {
    roster.requireFlat(flatId);
    if (amountPaise <= 0) {
      throw ProblemException.badRequest("INVALID_AMOUNT", "amountPaise must be positive");
    }
    return charges.save(new PendingCharge(flatId, "MANUAL", null, description.trim(), amountPaise, gstApplicable));
  }

  @Transactional
  public PendingCharge cancel(UUID id) {
    PendingCharge c = charges.findById(id).orElseThrow(() -> ProblemException.notFound("charge", id));
    if (!c.isPending()) {
      throw ProblemException.unprocessable("CHARGE_ALREADY_BILLED", "Only a pending charge can be cancelled");
    }
    c.cancel();
    return charges.save(c);
  }

  @Transactional(readOnly = true)
  public List<PendingCharge> list(UUID flatId) {
    return flatId == null ? charges.findTop200ByOrderByCreatedAtDesc() : charges.findByFlatIdOrderByCreatedAtDesc(flatId);
  }

  /** Pending charges per flat, oldest first. */
  @Transactional(readOnly = true)
  public Map<UUID, List<PendingCharge>> pendingByFlat() {
    return charges.findByStatusOrderByCreatedAtAsc("PENDING").stream()
        .collect(Collectors.groupingBy(PendingCharge::getFlatId));
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void markBilled(Collection<UUID> chargeIds, UUID billId) {
    for (PendingCharge c : charges.findByIdIn(chargeIds)) {
      if (c.isPending()) {
        c.billed(billId);
      }
    }
  }

  /** Chargeable booking confirmed: bill it with the next run (idempotent by booking id). */
  @Transactional(propagation = Propagation.MANDATORY)
  public void bookingConfirmed(Booking b) {
    if (b.chargePaise() <= 0 || b.flatId() == null
        || charges.findBySourceTypeAndSourceRef("BOOKING", b.bookingId()).isPresent()) {
      return;
    }
    String when = b.startsAt() == null ? "" : " on " + b.startsAt().toString().substring(0, 10);
    charges.save(new PendingCharge(b.flatId(), "BOOKING", b.bookingId(), "Facility booking" + when,
        b.chargePaise(), false));
  }

  /** A cancelled booking not yet billed is dropped; a billed one needs a credit note by accounts. */
  @Transactional(propagation = Propagation.MANDATORY)
  public void bookingCancelled(Booking b) {
    charges.findBySourceTypeAndSourceRef("BOOKING", b.bookingId()).filter(PendingCharge::isPending)
        .ifPresent(PendingCharge::cancel);
  }
}
