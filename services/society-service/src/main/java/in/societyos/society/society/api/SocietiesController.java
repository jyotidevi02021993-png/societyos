package in.societyos.society.society.api;

import in.societyos.society.society.api.SocietyController.ProfileRequest;
import in.societyos.society.society.api.SocietyController.ProfileResponse;
import in.societyos.society.society.application.SocietyService;
import in.societyos.society.society.domain.SocietySettings;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.net.URI;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Onboarding (platform admin) and the societies a user can switch between. */
@RestController
@RequestMapping("/v1/societies")
class SocietiesController {

  private final SocietyService societies;

  SocietiesController(SocietyService societies) {
    this.societies = societies;
  }

  record OnboardRequest(@NotBlank String name, String legalName, String address,
      @NotBlank String city, @NotBlank String state, String pin, String timezone, SocietySettings settings) {}

  /** Call without an active society: the permission only exists for SUPER_ADMIN on the platform. */
  @PostMapping
  @PreAuthorize("@perm.has('platform:society.create')")
  ResponseEntity<ProfileResponse> onboard(@Valid @RequestBody OnboardRequest r) {
    var profile = new ProfileRequest(r.name(), r.legalName(), r.address(), r.city(), r.state(), r.pin(), r.timezone());
    ProfileResponse created = ProfileResponse.from(societies.onboard(profile.toProfile(), r.settings()));
    return ResponseEntity.created(URI.create("/v1/society")).body(created);
  }

  @GetMapping
  @PreAuthorize("isAuthenticated()")
  List<ProfileResponse> readable() {
    return societies.readable().stream().map(ProfileResponse::from).toList();
  }
}
