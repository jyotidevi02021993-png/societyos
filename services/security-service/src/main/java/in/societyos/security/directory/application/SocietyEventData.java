package in.societyos.security.directory.application;

import com.fasterxml.jackson.annotation.JsonAlias;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** The fields security-service reads from society and identity events (unknown fields are ignored). */
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
      String status) {}

  public record Membership(UUID membershipId, UUID flatId, UUID userId, UUID residentId, String residentName,
      String kind, @JsonAlias("primary") Boolean isPrimary) {}

  public record Vehicle(UUID vehicleId, UUID flatId, String regNo, String kind, String rfidTag) {}

  public record DomesticStaff(UUID staffId, String name, String kind, List<UUID> flatIds, String kycStatus,
      UUID photoMediaId, String status) {}

  public record RoleAssigned(UUID assignmentId, UUID userId, String roleCode) {}
}
