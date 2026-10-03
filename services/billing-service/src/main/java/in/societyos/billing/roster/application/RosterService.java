package in.societyos.billing.roster.application;

import in.societyos.billing.platform.core.error.ProblemException;
import in.societyos.billing.platform.core.tenant.TenantContext;
import in.societyos.billing.roster.domain.BillingSettings;
import in.societyos.billing.roster.domain.FlatMember;
import in.societyos.billing.roster.domain.FlatRef;
import in.societyos.billing.roster.infrastructure.BillingSettingsRepository;
import in.societyos.billing.roster.infrastructure.FlatMemberRepository;
import in.societyos.billing.roster.infrastructure.FlatRefRepository;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Reads of the local roster copies, and billing settings. */
@Service
public class RosterService {

  private final FlatRefRepository flats;
  private final FlatMemberRepository members;
  private final BillingSettingsRepository settings;

  public RosterService(FlatRefRepository flats, FlatMemberRepository members, BillingSettingsRepository settings) {
    this.flats = flats;
    this.members = members;
    this.settings = settings;
  }

  @Transactional(readOnly = true)
  public List<FlatRef> flats() {
    return flats.findAllByOrderByLabelAsc();
  }

  @Transactional(readOnly = true)
  public FlatRef requireFlat(UUID flatId) {
    return flats.findById(flatId).orElseThrow(() -> ProblemException.notFound("flat", flatId));
  }

  @Transactional(readOnly = true)
  public Map<UUID, FlatRef> flatsById(Collection<UUID> ids) {
    if (ids.isEmpty()) {
      return Map.of();
    }
    return flats.findByIdInOrderByLabelAsc(ids).stream()
        .collect(Collectors.toMap(FlatRef::getId, Function.identity()));
  }

  @Transactional(readOnly = true)
  public List<UUID> flatIdsOf(UUID userId) {
    return members.findByUserIdAndEndedAtIsNull(userId).stream().map(FlatMember::getFlatId).distinct().toList();
  }

  @Transactional(readOnly = true)
  public boolean isMemberOf(UUID userId, UUID flatId) {
    return userId != null && members.existsByUserIdAndFlatIdAndEndedAtIsNull(userId, flatId);
  }

  /** Who hears about a flat's bills: its active members with a user account. */
  @Transactional(readOnly = true)
  public List<UUID> recipientsOf(UUID flatId) {
    return members.findByFlatIdAndEndedAtIsNull(flatId).stream().map(FlatMember::getUserId)
        .filter(Objects::nonNull).distinct().toList();
  }

  /** The active society's settings (defaults until society-service has told us otherwise). */
  @Transactional(readOnly = true)
  public BillingSettings settings() {
    UUID societyId = TenantContext.activeSocietyId();
    return settings.findById(societyId).orElseGet(() -> new BillingSettings(societyId));
  }

  @Transactional
  public BillingSettings configure(boolean gstRegistered, Integer gstRateBps, Long gstExemptionThresholdPaise,
      String lateFeeKind, Long lateFeeValue, Integer reminderDaysBefore) {
    UUID societyId = TenantContext.activeSocietyId();
    BillingSettings s = settings.findById(societyId).orElseGet(() -> new BillingSettings(societyId));
    String kind = lateFeeKind == null ? s.getLateFeeKind() : lateFeeKind.trim().toUpperCase();
    if (!BillingSettings.LATE_FEE_KINDS.contains(kind)) {
      throw ProblemException.badRequest("INVALID_LATE_FEE",
          "lateFeeKind must be one of " + BillingSettings.LATE_FEE_KINDS);
    }
    long value = lateFeeValue == null ? s.getLateFeeValue() : lateFeeValue;
    if (value < 0 || ("PERCENT".equals(kind) && value > 2_500)) {
      throw ProblemException.badRequest("INVALID_LATE_FEE", "A percentage late fee is at most 2500 bps (25%)");
    }
    int rate = gstRateBps == null ? s.getGstRateBps() : gstRateBps;
    if (rate < 0 || rate > 2_800) {
      throw ProblemException.badRequest("INVALID_GST_RATE", "gstRateBps must be between 0 and 2800");
    }
    long threshold = gstExemptionThresholdPaise == null ? s.getGstExemptionThresholdPaise() : gstExemptionThresholdPaise;
    int reminder = reminderDaysBefore == null ? s.getReminderDaysBefore() : reminderDaysBefore;
    if (threshold < 0 || reminder < 0 || reminder > 15) {
      throw ProblemException.badRequest("INVALID_SETTINGS", "threshold must be >= 0, reminderDaysBefore 0..15");
    }
    s.configure(gstRegistered, rate, threshold, kind, value, reminder);
    return settings.save(s);
  }
}
