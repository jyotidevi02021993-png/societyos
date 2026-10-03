package in.societyos.billing.roster.api;

import in.societyos.billing.roster.application.RosterService;
import in.societyos.billing.roster.domain.BillingSettings;
import in.societyos.billing.roster.domain.FlatRef;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
class BillingSettingsController {

  private final RosterService roster;

  BillingSettingsController(RosterService roster) {
    this.roster = roster;
  }

  record SettingsRequest(@NotNull Boolean gstRegistered, Integer gstRateBps, Long gstExemptionThresholdPaise,
      String lateFeeKind, Long lateFeeValue, Integer reminderDaysBefore) {}

  record SettingsResponse(int billingDueDay, int lateFeeGraceDays, boolean gstRegistered, int gstRateBps,
      long gstExemptionThresholdPaise, String lateFeeKind, long lateFeeValue, int reminderDaysBefore) {
    static SettingsResponse from(BillingSettings s) {
      return new SettingsResponse(s.getBillingDueDay(), s.getLateFeeGraceDays(), s.isGstRegistered(),
          s.getGstRateBps(), s.getGstExemptionThresholdPaise(), s.getLateFeeKind(), s.getLateFeeValue(),
          s.getReminderDaysBefore());
    }
  }

  record FlatResponse(UUID id, String label, Integer areaSqft, String flatType, String status) {
    static FlatResponse from(FlatRef f) {
      return new FlatResponse(f.getId(), f.getLabel(), f.getAreaSqft(), f.getFlatType(), f.getStatus());
    }
  }

  @GetMapping("/v1/billing/settings")
  @PreAuthorize("@perm.has('bill:view')")
  SettingsResponse get() {
    return SettingsResponse.from(roster.settings());
  }

  /** billingDueDay and lateFeeGraceDays are society settings (society-service) and read-only here. */
  @PutMapping("/v1/billing/settings")
  @PreAuthorize("@perm.has('bill:generate')")
  SettingsResponse put(@Valid @RequestBody SettingsRequest r) {
    return SettingsResponse.from(roster.configure(r.gstRegistered(), r.gstRateBps(),
        r.gstExemptionThresholdPaise(), r.lateFeeKind(), r.lateFeeValue(), r.reminderDaysBefore()));
  }

  /** The billing roster as copied from society-service. */
  @GetMapping("/v1/billing/flats")
  @PreAuthorize("@perm.has('bill:view')")
  List<FlatResponse> flats() {
    return roster.flats().stream().map(FlatResponse::from).toList();
  }
}
