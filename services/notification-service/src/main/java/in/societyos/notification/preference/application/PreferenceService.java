package in.societyos.notification.preference.application;

import in.societyos.notification.delivery.domain.QuietHours;
import in.societyos.notification.directory.application.RecipientDirectory;
import in.societyos.notification.platform.core.error.ProblemException;
import in.societyos.notification.platform.core.tenant.TenantContext;
import in.societyos.notification.preference.domain.ChannelRules;
import in.societyos.notification.preference.domain.Preference;
import in.societyos.notification.preference.infrastructure.PreferenceRepository;
import java.time.DateTimeException;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** Per-user, per-society notification preferences. */
@Service
public class PreferenceService {

  public static final Set<String> CATEGORIES = Set.of("GATE", "BILLING", "COMPLAINT", "JOBCARD", "NOTICE", "BOOKING",
      "APPROVAL", "ALERT", "SYSTEM", "*");

  /** What delivery needs to know about a recipient. */
  public record Settings(QuietHours quietHours, String language, Map<String, List<String>> disabled) {}

  private final PreferenceRepository preferences;
  private final RecipientDirectory directory;
  private final JsonMapper json;

  public PreferenceService(PreferenceRepository preferences, RecipientDirectory directory, JsonMapper json) {
    this.preferences = preferences;
    this.directory = directory;
    this.json = json;
  }

  @Transactional(readOnly = true)
  public Settings settingsFor(UUID userId) {
    ZoneId societyZone = directory.societyZone();
    return preferences.findBySocietyIdAndUserId(TenantContext.activeSocietyId(), userId)
        .map(p -> new Settings(new QuietHours(p.getQuietStart(), p.getQuietEnd(),
            p.getTimezone() == null ? societyZone : ZoneId.of(p.getTimezone())), p.getLanguage(), disabled(p)))
        .orElseGet(() -> new Settings(QuietHours.none(societyZone), null, Map.of()));
  }

  @Transactional(readOnly = true)
  public Preference mine() {
    UUID me = me();
    return preferences.findBySocietyIdAndUserId(TenantContext.activeSocietyId(), me).orElseGet(() -> new Preference(me));
  }

  @Transactional
  public Preference update(LocalTime quietStart, LocalTime quietEnd, String timezone, String language,
      Map<String, List<String>> disabled) {
    if ((quietStart == null) != (quietEnd == null)) {
      throw ProblemException.badRequest("INVALID_QUIET_HOURS", "Give both quietStart and quietEnd, or neither");
    }
    if (timezone != null) {
      try {
        ZoneId.of(timezone);
      } catch (DateTimeException e) {
        throw ProblemException.badRequest("INVALID_TIMEZONE", "Unknown timezone " + timezone);
      }
    }
    if (language != null && !Set.of("en", "hi").contains(language)) {
      throw ProblemException.badRequest("INVALID_LANGUAGE", "language must be en or hi");
    }
    Map<String, List<String>> off = new LinkedHashMap<>();
    if (disabled != null) {
      disabled.forEach((category, channels) -> {
        String c = category.trim().toUpperCase();
        if (!CATEGORIES.contains(c)) {
          throw ProblemException.badRequest("INVALID_CATEGORY", "Unknown category " + category);
        }
        List<String> ch = channels == null ? List.of() : channels.stream().map(s -> s.trim().toUpperCase()).distinct().toList();
        if (!ChannelRules.CHANNELS.containsAll(ch)) {
          throw ProblemException.badRequest("INVALID_CHANNEL", "Channels are " + ChannelRules.CHANNELS);
        }
        off.put(c, ch);
      });
    }
    Preference p = mine();
    p.update(quietStart, quietEnd, timezone, language, json.writeValueAsString(off));
    return preferences.save(p);
  }

  public Map<String, List<String>> disabled(Preference p) {
    return json.readValue(p.getDisabledJson(), new TypeReference<Map<String, List<String>>>() {});
  }

  private static UUID me() {
    return TenantContext.userId()
        .orElseThrow(() -> ProblemException.forbidden("USER_REQUIRED", "A signed-in user is required"));
  }
}
