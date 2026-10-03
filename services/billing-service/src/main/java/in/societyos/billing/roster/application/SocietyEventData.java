package in.societyos.billing.roster.application;

import com.fasterxml.jackson.annotation.JsonAlias;
import java.util.Map;
import java.util.UUID;

/** The fields billing-service reads from society events (unknown fields are ignored). */
public final class SocietyEventData {

  private SocietyEventData() {}

  public record SocietyCreated(UUID societyId) {}

  public record SettingsUpdated(UUID societyId, Map<String, Object> settings) {
    public Integer intSetting(String name) {
      Object v = settings == null ? null : settings.get(name);
      if (v instanceof Number n) {
        return n.intValue();
      }
      if (v instanceof String s && !s.isBlank()) {
        try {
          return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
          return null;
        }
      }
      return null;
    }
  }

  public record Flat(UUID flatId, UUID towerId, String towerName, String number, String label, Integer floor,
      Integer areaSqft, String flatType, String status) {}

  public record Membership(UUID membershipId, UUID flatId, UUID userId, String kind,
      @JsonAlias("primary") Boolean isPrimary) {}
}
