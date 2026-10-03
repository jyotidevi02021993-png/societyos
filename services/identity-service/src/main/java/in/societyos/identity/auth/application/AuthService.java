package in.societyos.identity.auth.application;

import in.societyos.identity.auth.domain.Device;
import in.societyos.identity.auth.domain.RefreshToken;
import in.societyos.identity.auth.infrastructure.DeviceRepository;
import in.societyos.identity.auth.infrastructure.RefreshTokenRepository;
import in.societyos.identity.config.IdentityProperties;
import in.societyos.identity.role.infrastructure.UserSocietyDirectory;
import in.societyos.identity.user.domain.AppUser;
import in.societyos.identity.user.domain.UserRegistered;
import in.societyos.identity.user.infrastructure.AppUserRepository;
import in.societyos.identity.platform.core.Hashing;
import in.societyos.identity.platform.core.error.ProblemException;
import in.societyos.identity.platform.events.DomainEvents;
import in.societyos.identity.platform.security.RevokedTokenValidator;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Login (OTP and admin password + TOTP), token refresh with rotation, society switch, logout. */
@Service
public class AuthService {

  private static final Logger log = LoggerFactory.getLogger(AuthService.class);
  private static final SecureRandom RANDOM = new SecureRandom();

  public record DeviceInfo(Device.Platform platform, String name, String pushToken) {}

  public record SocietyRoles(UUID societyId, Set<String> roles) {}

  public record AuthResult(
      String accessToken,
      Instant accessTokenExpiresAt,
      String refreshToken,
      UUID userId,
      String name,
      boolean newUser,
      boolean platformAdmin,
      boolean mfaSetupRequired,
      UUID activeSocietyId,
      List<SocietyRoles> societies) {}

  private final OtpService otp;
  private final TokenService tokens;
  private final AppUserRepository users;
  private final DeviceRepository devices;
  private final RefreshTokenRepository refreshTokens;
  private final UserSocietyDirectory directory;
  private final DomainEvents events;
  private final PasswordEncoder passwords;
  private final StringRedisTemplate redis;
  private final IdentityProperties props;
  private final TransactionTemplate tx;

  public AuthService(
      OtpService otp,
      TokenService tokens,
      AppUserRepository users,
      DeviceRepository devices,
      RefreshTokenRepository refreshTokens,
      UserSocietyDirectory directory,
      DomainEvents events,
      PasswordEncoder passwords,
      StringRedisTemplate redis,
      IdentityProperties props,
      PlatformTransactionManager txManager) {
    this.otp = otp;
    this.tokens = tokens;
    this.users = users;
    this.devices = devices;
    this.refreshTokens = refreshTokens;
    this.directory = directory;
    this.events = events;
    this.passwords = passwords;
    this.redis = redis;
    this.props = props;
    this.tx = new TransactionTemplate(txManager);
  }

  public long requestOtp(String phoneE164, String lang) {
    return otp.request(phoneE164, lang == null ? "en" : lang);
  }

  /** Verifies the OTP, registers the user on first login, binds the device, issues tokens. */
  public AuthResult verifyOtp(String phoneE164, String code, DeviceInfo device) {
    otp.verify(phoneE164, code);
    return tx.execute(
        s -> {
          boolean[] created = {false};
          AppUser user =
              users.findByPhoneE164(phoneE164)
                  .orElseGet(
                      () -> {
                        created[0] = true;
                        AppUser u = users.save(AppUser.withPhone(phoneE164, null));
                        events.publishGlobal(new UserRegistered(u.getId(), "OTP", u.getPreferredLang()));
                        return u;
                      });
          requireActive(user);
          Device d = devices.save(Device.bind(user.getId(), device.platform(), device.name(), device.pushToken()));
          return issue(user, d.getId(), null, List.of("otp"), created[0], false);
        });
  }

  /** Admin portal login: e-mail + Argon2id password, then TOTP when enrolled. */
  public AuthResult loginWithPassword(String email, String password, String totpCode, DeviceInfo device) {
    return tx.execute(
        s -> {
          AppUser user =
              users.findByEmailIgnoreCase(email)
                  .filter(u -> u.getPasswordHash() != null && passwords.matches(password, u.getPasswordHash()))
                  .orElseThrow(() -> unauthorized("INVALID_CREDENTIALS", "E-mail or password is not correct"));
          requireActive(user);
          List<String> amr = List.of("pwd");
          if (user.hasMfa()) {
            if (totpCode == null || totpCode.isBlank()) {
              throw unauthorized("MFA_REQUIRED", "Enter the code from your authenticator app");
            }
            if (!Totp.verify(user.getMfaSecret(), totpCode, Instant.now())) {
              throw unauthorized("MFA_INVALID", "Authenticator code is not correct");
            }
            amr = List.of("pwd", "mfa");
          }
          Device d = devices.save(Device.bind(user.getId(), device.platform(), device.name(), null));
          return issue(user, d.getId(), null, amr, false, !user.hasMfa());
        });
  }

  /**
   * Rotates the refresh token. A token that was already rotated is proof of theft: the device's
   * whole session is revoked (committed) and the call fails.
   */
  public AuthResult refresh(String refreshToken) {
    String hash = Hashing.sha256Hex(refreshToken);
    Object outcome =
        tx.execute(
            s -> {
              RefreshToken token = refreshTokens.findByTokenHash(hash).orElse(null);
              if (token == null) {
                return "INVALID";
              }
              if (token.isSpent()) {
                refreshTokens.revokeAllForDevice(token.getDeviceId());
                devices.findById(token.getDeviceId()).ifPresent(Device::revoke);
                log.warn("Refresh token reuse detected for device {}; session revoked", token.getDeviceId());
                return "REUSED";
              }
              if (token.isExpired(Instant.now())) {
                return "EXPIRED";
              }
              Device device = devices.findById(token.getDeviceId()).orElse(null);
              if (device == null || !device.isActiveFor(token.getUserId())) {
                return "INVALID";
              }
              AppUser user = users.findById(token.getUserId()).orElseThrow();
              if (user.isBlocked()) {
                return "INVALID";
              }
              token.markRotated();
              device.seen();
              return issue(user, device.getId(), null, List.of("refresh"), false, false);
            });
    if (outcome instanceof AuthResult result) {
      return result;
    }
    throw switch ((String) outcome) {
      case "REUSED" -> unauthorized("REFRESH_TOKEN_REUSED", "Session revoked, please log in again");
      case "EXPIRED" -> unauthorized("REFRESH_TOKEN_EXPIRED", "Session expired, please log in again");
      default -> unauthorized("REFRESH_TOKEN_INVALID", "Please log in again");
    };
  }

  /** New access token for another society the user belongs to (platform admins: any society). */
  public AuthResult switchSociety(UUID userId, UUID deviceId, UUID societyId, List<String> amr) {
    return tx.execute(
        s -> {
          AppUser user = users.findById(userId).orElseThrow(() -> unauthorized("USER_NOT_FOUND", "Unknown user"));
          requireActive(user);
          Map<UUID, Set<String>> societies = directory.societiesOf(userId);
          if (!societies.containsKey(societyId) && !user.isPlatformAdmin()) {
            throw ProblemException.forbidden("SOCIETY_NOT_ALLOWED", "You are not a member of this society");
          }
          user.rememberSociety(societyId);
          TokenService.AccessToken access = tokens.issue(user, deviceId, societies, societyId, amr);
          return result(user, access, null, societies, societyId, false, false);
        });
  }

  /** Denies the current access token until it expires and revokes the device's refresh tokens. */
  public void logout(UUID deviceId, String jti, Instant accessExpiresAt) {
    if (jti != null && accessExpiresAt != null) {
      Duration ttl = Duration.between(Instant.now(), accessExpiresAt);
      if (!ttl.isNegative() && !ttl.isZero()) {
        try {
          redis.opsForValue().set(RevokedTokenValidator.PREFIX + jti, "1", ttl);
        } catch (RuntimeException e) {
          log.warn("Could not deny-list token {}: {}", jti, e.getMessage());
        }
      }
    }
    if (deviceId != null) {
      tx.executeWithoutResult(
          s -> {
            refreshTokens.revokeAllForDevice(deviceId);
            devices.findById(deviceId).ifPresent(Device::revoke);
          });
    }
  }

  private AuthResult issue(
      AppUser user, UUID deviceId, UUID requestedSociety, List<String> amr, boolean newUser, boolean mfaSetupRequired) {
    Map<UUID, Set<String>> societies = directory.societiesOf(user.getId());
    UUID active = chooseActive(user, societies, requestedSociety);
    if (active != null) {
      user.rememberSociety(active);
    }
    TokenService.AccessToken access = tokens.issue(user, deviceId, societies, active, amr);
    String refresh = newRefreshToken();
    refreshTokens.save(
        RefreshToken.issue(user.getId(), deviceId, Hashing.sha256Hex(refresh), Instant.now().plus(props.refreshTokenTtl())));
    return result(user, access, refresh, societies, active, newUser, mfaSetupRequired);
  }

  private static AuthResult result(
      AppUser user,
      TokenService.AccessToken access,
      String refresh,
      Map<UUID, Set<String>> societies,
      UUID active,
      boolean newUser,
      boolean mfaSetupRequired) {
    List<SocietyRoles> list = societies.entrySet().stream().map(e -> new SocietyRoles(e.getKey(), e.getValue())).toList();
    return new AuthResult(
        access.value(), access.expiresAt(), refresh, user.getId(), user.getName(), newUser,
        user.isPlatformAdmin(), mfaSetupRequired, active, list);
  }

  private static UUID chooseActive(AppUser user, Map<UUID, Set<String>> societies, UUID requested) {
    if (requested != null && societies.containsKey(requested)) {
      return requested;
    }
    if (user.getLastSocietyId() != null && societies.containsKey(user.getLastSocietyId())) {
      return user.getLastSocietyId();
    }
    return societies.keySet().stream().findFirst().orElse(null);
  }

  private static String newRefreshToken() {
    byte[] bytes = new byte[32];
    RANDOM.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  private static void requireActive(AppUser user) {
    if (user.isBlocked()) {
      throw ProblemException.forbidden("USER_BLOCKED", "This account is blocked; contact your society office");
    }
  }

  private static ProblemException unauthorized(String code, String message) {
    return new ProblemException(code, HttpStatus.UNAUTHORIZED, message);
  }
}
