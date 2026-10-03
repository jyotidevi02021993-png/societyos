package in.societyos.identity.auth.api;

import in.societyos.identity.auth.application.AuthService;
import in.societyos.identity.auth.application.AuthService.AuthResult;
import in.societyos.identity.auth.application.AuthService.DeviceInfo;
import in.societyos.identity.auth.application.ServiceTokenService;
import in.societyos.identity.auth.domain.Device;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/auth")
class AuthController {

  static final String E164 = "^\\+[1-9]\\d{7,14}$";

  private final AuthService auth;
  private final ServiceTokenService serviceTokens;

  AuthController(AuthService auth, ServiceTokenService serviceTokens) {
    this.auth = auth;
    this.serviceTokens = serviceTokens;
  }

  /** Client-credentials token for service-to-service calls without a user. */
  @PostMapping("/service-token")
  ServiceTokenResponse serviceToken(@Valid @RequestBody ServiceTokenRequest req) {
    var t = serviceTokens.issue(req.clientId(), req.clientSecret(), req.scopes());
    return new ServiceTokenResponse(t.value(), t.expiresAt(), "Bearer");
  }

  record OtpRequest(
      @NotBlank @Pattern(regexp = E164, message = "phone must be E.164, e.g. +919876543210") String phone,
      @Pattern(regexp = "en|hi") String lang) {}

  record OtpRequested(long expiresInSeconds) {}

  record DeviceDto(@NotNull Device.Platform platform, @Size(max = 100) String name, @Size(max = 512) String pushToken) {
    DeviceInfo toInfo() {
      return new DeviceInfo(platform, name, pushToken);
    }
  }

  record OtpVerify(
      @NotBlank @Pattern(regexp = E164) String phone,
      @NotBlank @Pattern(regexp = "\\d{4,8}") String code,
      @NotNull @Valid DeviceDto device) {}

  record PasswordLogin(@NotBlank @Email String email, @NotBlank String password, String totp) {}

  record RefreshRequest(@NotBlank String refreshToken) {}

  record SwitchSociety(@NotNull UUID societyId) {}

  record ServiceTokenRequest(@NotBlank String clientId, @NotBlank String clientSecret, List<String> scopes) {}

  record ServiceTokenResponse(String accessToken, java.time.Instant expiresAt, String tokenType) {}

  @PostMapping("/otp/request")
  OtpRequested requestOtp(@Valid @RequestBody OtpRequest req) {
    return new OtpRequested(auth.requestOtp(req.phone(), req.lang()));
  }

  @PostMapping("/otp/verify")
  AuthResult verifyOtp(@Valid @RequestBody OtpVerify req) {
    return auth.verifyOtp(req.phone(), req.code(), req.device().toInfo());
  }

  @PostMapping("/login")
  AuthResult login(@Valid @RequestBody PasswordLogin req) {
    return auth.loginWithPassword(req.email(), req.password(), req.totp(), new DeviceInfo(Device.Platform.WEB, "Web portal", null));
  }

  @PostMapping("/token/refresh")
  AuthResult refresh(@Valid @RequestBody RefreshRequest req) {
    return auth.refresh(req.refreshToken());
  }

  @PostMapping("/switch-society")
  AuthResult switchSociety(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody SwitchSociety req) {
    List<String> amr = jwt.getClaimAsStringList("amr");
    return auth.switchSociety(UUID.fromString(jwt.getSubject()), deviceId(jwt), req.societyId(), amr == null ? List.of() : amr);
  }

  @PostMapping("/logout")
  ResponseEntity<Void> logout(@AuthenticationPrincipal Jwt jwt) {
    auth.logout(deviceId(jwt), jwt.getId(), jwt.getExpiresAt());
    return ResponseEntity.noContent().build();
  }

  private static UUID deviceId(Jwt jwt) {
    String did = jwt.getClaimAsString("did");
    return did == null ? null : UUID.fromString(did);
  }
}
