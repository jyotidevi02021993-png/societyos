package in.societyos.identity.user.api;

import in.societyos.identity.user.application.UserService;
import in.societyos.identity.platform.core.error.ProblemException;
import in.societyos.identity.platform.core.tenant.TenantContext;
import in.societyos.identity.platform.security.PermissionResolver;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The signed-in user: profile, societies, permissions (used by every other service), MFA, push token. */
@RestController
@RequestMapping("/v1/me")
class MeController {

  private final UserService users;
  private final PermissionResolver permissions;

  MeController(UserService users, PermissionResolver permissions) {
    this.users = users;
    this.permissions = permissions;
  }

  record ProfileUpdate(@Size(max = 120) String name, @Pattern(regexp = "en|hi") String preferredLang) {}

  record Permissions(UUID societyId, List<String> permissions) {}

  record MfaConfirm(@NotBlank @Pattern(regexp = "\\d{6}") String code) {}

  record PushToken(@NotBlank @Size(max = 512) String pushToken) {}

  @GetMapping
  UserService.Me me() {
    return users.me(currentUser());
  }

  @PatchMapping
  UserService.Me update(@Valid @RequestBody ProfileUpdate req) {
    return users.updateProfile(currentUser(), req.name(), req.preferredLang());
  }

  /** Called by RemotePermissionResolver in every service (with the user's own token). */
  @GetMapping("/permissions")
  Permissions permissions() {
    UUID society = TenantContext.activeSocietyId();
    return new Permissions(society, List.copyOf(permissions.permissions(currentUser(), society)));
  }

  @PostMapping("/mfa/setup")
  UserService.MfaSetup startMfa() {
    return users.startMfaSetup(currentUser());
  }

  @PostMapping("/mfa/confirm")
  ResponseEntity<Void> confirmMfa(@Valid @RequestBody MfaConfirm req) {
    users.confirmMfa(currentUser(), req.code());
    return ResponseEntity.noContent().build();
  }

  @PutMapping("/device/push-token")
  ResponseEntity<Void> pushToken(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody PushToken req) {
    String did = jwt.getClaimAsString("did");
    if (did == null) {
      throw ProblemException.badRequest("NO_DEVICE", "Token is not bound to a device");
    }
    users.updatePushToken(currentUser(), UUID.fromString(did), req.pushToken());
    return ResponseEntity.noContent().build();
  }

  private static UUID currentUser() {
    return TenantContext.userId().orElseThrow();
  }
}
