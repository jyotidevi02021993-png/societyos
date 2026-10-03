package in.societyos.notification.directory.application;

import in.societyos.notification.directory.domain.DeviceToken;
import in.societyos.notification.directory.domain.RecipientProfile;
import in.societyos.notification.directory.domain.RoleMember;
import in.societyos.notification.directory.domain.SocietySettings;
import in.societyos.notification.directory.infrastructure.DeviceTokenRepository;
import in.societyos.notification.directory.infrastructure.RecipientProfileRepository;
import in.societyos.notification.directory.infrastructure.RoleMemberRepository;
import in.societyos.notification.directory.infrastructure.SocietySettingsRepository;
import in.societyos.notification.platform.core.tenant.TenantContext;
import java.time.ZoneId;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read models copied from identity and society events: role holders, society settings, push
 * tokens and preferred languages.
 */
@Service
public class RecipientDirectory {

  private final RoleMemberRepository roles;
  private final SocietySettingsRepository settings;
  private final DeviceTokenRepository devices;
  private final RecipientProfileRepository profiles;
  private final ZoneId defaultZone;

  public RecipientDirectory(RoleMemberRepository roles, SocietySettingsRepository settings,
      DeviceTokenRepository devices, RecipientProfileRepository profiles,
      @Value("${sos.default-timezone:Asia/Kolkata}") String defaultZone) {
    this.roles = roles;
    this.settings = settings;
    this.devices = devices;
    this.profiles = profiles;
    this.defaultZone = ZoneId.of(defaultZone);
  }

  // --- event handlers ------------------------------------------------------------------------

  @Transactional(propagation = Propagation.MANDATORY)
  public void roleAssigned(UUID assignmentId, UUID userId, String roleCode) {
    if (assignmentId != null && userId != null && roleCode != null && !roles.existsById(assignmentId)) {
      roles.save(new RoleMember(assignmentId, userId, roleCode));
    }
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void roleRevoked(UUID assignmentId) {
    if (assignmentId != null) {
      roles.findById(assignmentId).ifPresent(roles::delete);
    }
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void settingsChanged(UUID societyId, String timezone, Integer retentionDays) {
    SocietySettings s = settings.findById(societyId).orElseGet(() -> new SocietySettings(societyId));
    s.apply(timezone, retentionDays);
    settings.save(s);
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void deviceRegistered(UUID deviceId, UUID userId, String platform, String pushToken) {
    if (deviceId == null || userId == null || pushToken == null || pushToken.isBlank()) {
      return;
    }
    devices.findByDeviceId(deviceId).ifPresentOrElse(
        d -> d.update(userId, platform == null ? "UNKNOWN" : platform, pushToken),
        () -> devices.save(new DeviceToken(deviceId, userId, platform == null ? "UNKNOWN" : platform, pushToken)));
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void userRegistered(UUID userId, String preferredLang) {
    if (userId == null) {
      return;
    }
    profiles.findByUserId(userId).ifPresentOrElse(
        p -> p.setPreferredLang(preferredLang),
        () -> profiles.save(new RecipientProfile(userId, preferredLang)));
  }

  // --- queries -------------------------------------------------------------------------------

  /** Users holding any of these roles in the active society. */
  @Transactional(readOnly = true)
  public Set<UUID> usersWithRoles(Collection<String> roleCodes) {
    if (roleCodes == null || roleCodes.isEmpty()) {
      return Set.of();
    }
    Set<UUID> out = new LinkedHashSet<>();
    roles.findBySocietyIdAndRoleCodeIn(TenantContext.activeSocietyId(), roleCodes)
        .forEach((RoleMember r) -> out.add(r.getUserId()));
    return out;
  }

  @Transactional(readOnly = true)
  public List<DeviceToken> devicesOf(UUID userId) {
    return devices.findByUserId(userId);
  }

  @Transactional(readOnly = true)
  public Optional<String> languageOf(UUID userId) {
    return profiles.findByUserId(userId).map(RecipientProfile::getPreferredLang);
  }

  /** The active society's zone (settings) or the platform default. */
  @Transactional(readOnly = true)
  public ZoneId societyZone() {
    return settings.findById(TenantContext.activeSocietyId()).map(SocietySettings::getTimezone)
        .filter(z -> !z.isBlank()).map(ZoneId::of).orElse(defaultZone);
  }

  @Transactional(readOnly = true)
  public int retentionDays() {
    return settings.findById(TenantContext.activeSocietyId()).map(SocietySettings::getNotificationRetentionDays)
        .orElse(SocietySettings.DEFAULT_RETENTION_DAYS);
  }
}
