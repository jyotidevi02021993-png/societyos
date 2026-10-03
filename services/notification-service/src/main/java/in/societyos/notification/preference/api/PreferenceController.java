package in.societyos.notification.preference.api;

import in.societyos.notification.preference.application.PreferenceService;
import in.societyos.notification.preference.domain.Preference;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The caller's own notification preferences in the active society. */
@RestController
@RequestMapping("/v1/me/notification-preferences")
class PreferenceController {

  private final PreferenceService preferences;

  PreferenceController(PreferenceService preferences) {
    this.preferences = preferences;
  }

  /**
   * @param disabled channels switched off per category ({@code "*"} = every category), e.g.
   *     {@code {"BILLING": ["SMS"], "*": ["WHATSAPP"]}}
   */
  record PreferenceDto(LocalTime quietStart, LocalTime quietEnd, String timezone, String language,
      Map<String, List<String>> disabled) {}

  @GetMapping
  @PreAuthorize("isAuthenticated()")
  PreferenceDto get() {
    return dto(preferences.mine());
  }

  @PutMapping
  @PreAuthorize("isAuthenticated()")
  PreferenceDto put(@RequestBody PreferenceDto r) {
    return dto(preferences.update(r.quietStart(), r.quietEnd(), r.timezone(), r.language(), r.disabled()));
  }

  private PreferenceDto dto(Preference p) {
    return new PreferenceDto(p.getQuietStart(), p.getQuietEnd(), p.getTimezone(), p.getLanguage(),
        preferences.disabled(p));
  }
}
