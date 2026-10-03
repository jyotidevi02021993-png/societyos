package in.societyos.identity.user.application;

import in.societyos.identity.auth.application.Totp;
import in.societyos.identity.auth.domain.Device;
import in.societyos.identity.auth.infrastructure.DeviceRepository;
import in.societyos.identity.config.IdentityProperties;
import in.societyos.identity.role.infrastructure.UserSocietyDirectory;
import in.societyos.identity.user.domain.AppUser;
import in.societyos.identity.user.domain.DeviceRegistered;
import in.societyos.identity.user.domain.UserRegistered;
import in.societyos.identity.user.infrastructure.AppUserRepository;
import in.societyos.identity.platform.core.error.ProblemException;
import in.societyos.identity.platform.events.DomainEvents;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserService {

  public record SocietyRoles(UUID societyId, Set<String> roles) {}

  public record Me(
      UUID id,
      String name,
      String phone,
      String email,
      String preferredLang,
      boolean platformAdmin,
      boolean mfaEnabled,
      UUID lastSocietyId,
      List<SocietyRoles> societies) {}

  public record MfaSetup(String secret, String otpauthUri) {}

  /** For notification delivery only (service token with scope users:contact). */
  public record Contact(UUID userId, String phone, String email, String preferredLang, String name) {}

  private final AppUserRepository users;
  private final DeviceRepository devices;
  private final UserSocietyDirectory directory;
  private final DomainEvents events;
  private final StringRedisTemplate redis;
  private final IdentityProperties props;

  public UserService(
      AppUserRepository users,
      DeviceRepository devices,
      UserSocietyDirectory directory,
      DomainEvents events,
      StringRedisTemplate redis,
      IdentityProperties props) {
    this.users = users;
    this.devices = devices;
    this.directory = directory;
    this.events = events;
    this.redis = redis;
    this.props = props;
  }

  @Transactional(readOnly = true)
  public Me me(UUID userId) {
    AppUser u = get(userId);
    Map<UUID, Set<String>> societies = directory.societiesOf(userId);
    return new Me(
        u.getId(), u.getName(), u.getPhoneE164(), u.getEmail(), u.getPreferredLang(), u.isPlatformAdmin(),
        u.hasMfa(), u.getLastSocietyId(),
        societies.entrySet().stream().map(e -> new SocietyRoles(e.getKey(), e.getValue())).toList());
  }

  @Transactional
  public Me updateProfile(UUID userId, String name, String preferredLang) {
    try {
      get(userId).updateProfile(name, preferredLang);
    } catch (IllegalArgumentException e) {
      throw ProblemException.badRequest("INVALID_PROFILE", e.getMessage());
    }
    return me(userId);
  }

  /**
   * Finds or pre-registers a person by phone, so society-service can link a resident or staff
   * member to a user before they ever log in. Returns the user id only.
   */
  @Transactional
  public UUID resolveByPhone(String phoneE164, String name) {
    return users.findByPhoneE164(phoneE164)
        .map(AppUser::getId)
        .orElseGet(
            () -> {
              AppUser u = users.save(AppUser.withPhone(phoneE164, name));
              events.publishGlobal(new UserRegistered(u.getId(), "INVITE", u.getPreferredLang()));
              return u.getId();
            });
  }

  @Transactional(readOnly = true)
  public Contact contact(UUID userId) {
    AppUser u = get(userId);
    return new Contact(u.getId(), u.getPhoneE164(), u.getEmail(), u.getPreferredLang(), u.getName());
  }

  /** Step 1 of TOTP enrolment: the secret waits in Redis until a first code confirms it. */
  public MfaSetup startMfaSetup(UUID userId) {
    AppUser u = get(userId);
    String secret = Totp.newSecret();
    redis.opsForValue().set("mfa:pending:" + userId, secret, Duration.ofMinutes(10));
    String account = u.getEmail() != null ? u.getEmail() : u.getId().toString();
    return new MfaSetup(secret, Totp.otpauthUri("SocietyOS", account, secret));
  }

  @Transactional
  public void confirmMfa(UUID userId, String code) {
    String secret = redis.opsForValue().get("mfa:pending:" + userId);
    if (secret == null) {
      throw ProblemException.badRequest("MFA_SETUP_EXPIRED", "Start the authenticator setup again");
    }
    if (!Totp.verify(secret, code, Instant.now())) {
      throw ProblemException.badRequest("MFA_INVALID", "Authenticator code is not correct");
    }
    get(userId).setMfaSecret(secret);
    redis.delete("mfa:pending:" + userId);
  }

  @Transactional
  public void updatePushToken(UUID userId, UUID deviceId, String pushToken) {
    Device d =
        devices.findById(deviceId)
            .filter(x -> x.isActiveFor(userId))
            .orElseThrow(() -> ProblemException.notFound("Device", deviceId));
    d.updatePushToken(pushToken);
    d.seen();
    events.publishGlobal(new DeviceRegistered(deviceId, userId, d.getPlatform().name(), pushToken));
  }

  private AppUser get(UUID userId) {
    return users.findById(userId).orElseThrow(() -> ProblemException.notFound("User", userId));
  }
}
