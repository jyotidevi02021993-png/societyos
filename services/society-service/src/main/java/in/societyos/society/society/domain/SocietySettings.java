package in.societyos.society.society.domain;

import in.societyos.society.platform.core.error.ProblemException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Per-society configuration (stored as JSONB on {@code society.settings}). Every field has a
 * default, so a society created without settings is fully usable. {@code features} holds
 * per-society feature flags.
 */
public record SocietySettings(
    Integer gateApprovalTimeoutSeconds,
    Integer visitorRetentionDays,
    Integer gateLogRetentionDays,
    Integer notificationRetentionDays,
    Integer billingDueDay,
    Integer lateFeeGraceDays,
    Boolean directoryEnabled,
    Map<String, Boolean> features) {

  public SocietySettings {
    features = features == null ? Map.of() : Map.copyOf(features);
  }

  public static SocietySettings defaults() {
    return new SocietySettings(120, 180, 180, 90, 10, 15, true, Map.of());
  }

  /** Applies the non-null fields of {@code patch} on top of this; feature flags are merged key by key. */
  public SocietySettings merge(SocietySettings patch) {
    if (patch == null) {
      return this;
    }
    Map<String, Boolean> mergedFeatures = new LinkedHashMap<>(features);
    mergedFeatures.putAll(patch.features());
    return new SocietySettings(
        pick(patch.gateApprovalTimeoutSeconds, gateApprovalTimeoutSeconds),
        pick(patch.visitorRetentionDays, visitorRetentionDays),
        pick(patch.gateLogRetentionDays, gateLogRetentionDays),
        pick(patch.notificationRetentionDays, notificationRetentionDays),
        pick(patch.billingDueDay, billingDueDay),
        pick(patch.lateFeeGraceDays, lateFeeGraceDays),
        pick(patch.directoryEnabled, directoryEnabled),
        mergedFeatures);
  }

  /** Fills missing fields from the defaults and checks every range. */
  public SocietySettings validated() {
    SocietySettings s = defaults().merge(this);
    range("gateApprovalTimeoutSeconds", s.gateApprovalTimeoutSeconds, 30, 900);
    range("visitorRetentionDays", s.visitorRetentionDays, 7, 730);
    range("gateLogRetentionDays", s.gateLogRetentionDays, 30, 3650);
    range("notificationRetentionDays", s.notificationRetentionDays, 7, 365);
    // Days 29-31 do not exist in every month, so bills would fall due on different days.
    range("billingDueDay", s.billingDueDay, 1, 28);
    range("lateFeeGraceDays", s.lateFeeGraceDays, 0, 60);
    return s;
  }

  private static <T> T pick(T preferred, T fallback) {
    return preferred != null ? preferred : fallback;
  }

  private static void range(String field, int value, int min, int max) {
    if (value < min || value > max) {
      throw ProblemException.badRequest(
          "INVALID_SETTINGS", "%s must be between %d and %d".formatted(field, min, max));
    }
  }
}
