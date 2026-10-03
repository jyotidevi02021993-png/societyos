package in.societyos.society.society.api;

import in.societyos.society.society.application.SocietyService;
import in.societyos.society.society.domain.Society;
import in.societyos.society.society.domain.SocietySettings;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The active society's profile and settings. */
@RestController
@RequestMapping("/v1/society")
class SocietyController {

  private final SocietyService societies;

  SocietyController(SocietyService societies) {
    this.societies = societies;
  }

  record ProfileRequest(@NotBlank String name, String legalName, String address,
      @NotBlank String city, @NotBlank String state, String pin, String timezone) {
    Society.Profile toProfile() {
      return new Society.Profile(name, legalName, address, city, state, pin, timezone);
    }
  }

  record ProfileResponse(UUID id, String name, String legalName, String address,
      String city, String state, String pin, String timezone, String status) {
    static ProfileResponse from(Society society) {
      Society.Profile p = society.profile();
      return new ProfileResponse(society.getId(), p.name(), p.legalName(), p.address(),
          p.city(), p.state(), p.pin(), p.timezone(), society.getStatus());
    }
  }

  record SettingsResponse(UUID societyId, SocietySettings settings) {}

  @GetMapping
  @PreAuthorize("@perm.has('society:view')")
  ProfileResponse profile() {
    return ProfileResponse.from(societies.current());
  }

  @PutMapping
  @PreAuthorize("@perm.has('society:manage')")
  ProfileResponse updateProfile(@Valid @RequestBody ProfileRequest request) {
    return ProfileResponse.from(societies.updateProfile(request.toProfile()));
  }

  @GetMapping("/settings")
  @PreAuthorize("@perm.has('society:view')")
  SettingsResponse settings() {
    return new SettingsResponse(societies.current().getId(), societies.settings());
  }

  @PutMapping("/settings")
  @PreAuthorize("@perm.hasAny('society:manage', 'settings:manage')")
  SettingsResponse updateSettings(@RequestBody SocietySettings patch) {
    SocietySettings settings = societies.updateSettings(patch);
    return new SettingsResponse(societies.current().getId(), settings);
  }
}
