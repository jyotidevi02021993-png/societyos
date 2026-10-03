package in.societyos.notification.template.application;

import in.societyos.notification.template.domain.NotificationTemplate;
import in.societyos.notification.template.domain.TemplateRenderer;
import in.societyos.notification.template.domain.TemplateRenderer.Rendered;
import in.societyos.notification.template.infrastructure.NotificationTemplateRepository;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Renders a request for one channel and language. Lookup order: (channel, lang), (ANY, lang),
 * (channel, en), (ANY, en), then a generic fallback built from the params.
 */
@Service
public class TemplateService {

  private final NotificationTemplateRepository templates;

  public TemplateService(NotificationTemplateRepository templates) {
    this.templates = templates;
  }

  @Transactional(readOnly = true)
  public Rendered render(String code, String channel, String lang, String category, Map<String, String> params) {
    List<NotificationTemplate> all = templates.findByCode(code);
    String l = "hi".equals(lang) ? "hi" : "en";
    return find(all, channel, l)
        .or(() -> find(all, NotificationTemplate.ANY, l))
        .or(() -> find(all, channel, "en"))
        .or(() -> find(all, NotificationTemplate.ANY, "en"))
        .map(t -> TemplateRenderer.render(t.getTitle(), t.getBody(), params))
        .orElseGet(() -> TemplateRenderer.fallback(category, code, params));
  }

  @Transactional(readOnly = true)
  public List<NotificationTemplate> list() {
    return templates.findAllByOrderByCodeAscChannelAscLangAsc();
  }

  /** Creates or replaces one template (platform admins). */
  @Transactional
  public NotificationTemplate upsert(String code, String channel, String lang, String title, String body) {
    NotificationTemplate t = find(templates.findByCode(code), channel, lang)
        .orElseGet(() -> new NotificationTemplate(code, channel, lang, title, body));
    t.update(title, body);
    return templates.save(t);
  }

  private static Optional<NotificationTemplate> find(List<NotificationTemplate> all, String channel, String lang) {
    return all.stream().filter(t -> t.getChannel().equals(channel) && t.getLang().equals(lang))
        .min(Comparator.comparing(NotificationTemplate::getCode));
  }
}
