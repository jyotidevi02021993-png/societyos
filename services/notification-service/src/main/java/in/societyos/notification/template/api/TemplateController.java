package in.societyos.notification.template.api;

import in.societyos.notification.template.application.TemplateService;
import in.societyos.notification.template.domain.NotificationTemplate;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Platform-wide message templates (en, hi), managed by platform admins. */
@RestController
@RequestMapping("/v1/templates")
class TemplateController {

  private final TemplateService templates;

  TemplateController(TemplateService templates) {
    this.templates = templates;
  }

  record TemplateRequest(@NotBlank @Size(max = 120) String code,
      @Pattern(regexp = "ANY|PUSH|SMS|WHATSAPP|EMAIL|INAPP") String channel,
      @Pattern(regexp = "en|hi") String lang, @NotBlank @Size(max = 200) String title,
      @NotBlank @Size(max = 4000) String body) {}

  record TemplateResponse(UUID id, String code, String channel, String lang, String title, String body) {
    static TemplateResponse from(NotificationTemplate t) {
      return new TemplateResponse(t.getId(), t.getCode(), t.getChannel(), t.getLang(), t.getTitle(), t.getBody());
    }
  }

  @GetMapping
  @PreAuthorize("@perm.has('platform:notification-templates')")
  List<TemplateResponse> list() {
    return templates.list().stream().map(TemplateResponse::from).toList();
  }

  @PutMapping
  @PreAuthorize("@perm.has('platform:notification-templates')")
  TemplateResponse upsert(@Valid @RequestBody TemplateRequest r) {
    return TemplateResponse.from(templates.upsert(r.code().trim(), r.channel() == null ? "ANY" : r.channel(),
        r.lang() == null ? "en" : r.lang(), r.title(), r.body()));
  }
}
