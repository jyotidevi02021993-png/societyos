package in.societyos.billing.platform.core.tenant;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Who is acting and in which societies.
 *
 * @param userId acting user; null for the system
 * @param actorType USER, SERVICE or SYSTEM
 * @param activeSocietyId society that writes go to; null for read-only or platform work
 * @param readableSocietyIds societies whose rows may be read (RLS {@code app.society_ids})
 * @param roles role codes from the token
 * @param bearerToken the caller's access token, for forwarding to another service; may be null
 */
public record Tenant(
    UUID userId,
    ActorType actorType,
    UUID activeSocietyId,
    List<UUID> readableSocietyIds,
    Set<String> roles,
    String bearerToken) {

  public enum ActorType {
    USER,
    SERVICE,
    SYSTEM
  }

  public Tenant {
    Objects.requireNonNull(actorType, "actorType");
    readableSocietyIds = readableSocietyIds == null ? List.of() : List.copyOf(readableSocietyIds);
    roles = roles == null ? Set.of() : Set.copyOf(roles);
    if (activeSocietyId != null && !readableSocietyIds.contains(activeSocietyId)) {
      throw new IllegalArgumentException("active society must be readable");
    }
  }

  /** System actor working inside one society (Kafka consumers, scheduled jobs). */
  public static Tenant system(UUID societyId) {
    return new Tenant(null, ActorType.SYSTEM, societyId, List.of(societyId), Set.of(), null);
  }

  /** System actor with no society: sees no tenant rows (RLS returns nothing). */
  public static Tenant platform() {
    return new Tenant(null, ActorType.SYSTEM, null, List.of(), Set.of(), null);
  }

  public boolean hasRole(String role) {
    return roles.contains(role);
  }

  public UUID requireActiveSociety() {
    if (activeSocietyId == null) {
      throw new NoTenantException("No active society for this request");
    }
    return activeSocietyId;
  }
}
