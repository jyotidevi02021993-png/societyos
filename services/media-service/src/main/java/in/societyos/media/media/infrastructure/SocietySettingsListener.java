package in.societyos.media.media.infrastructure;

import in.societyos.media.media.application.RetentionSettings;
import in.societyos.media.platform.events.CloudEvent;
import in.societyos.media.platform.events.DomainEventListener;
import org.springframework.stereotype.Component;

/** Keeps the society's visitor-photo retention in step with society-service. DLQ: sos.dlq.media.society-settings. */
@Component
public class SocietySettingsListener {

  public record Settings(Integer visitorRetentionDays) {}

  public record SettingsUpdated(java.util.UUID societyId, Settings settings) {}

  private final RetentionSettings retention;

  public SocietySettingsListener(RetentionSettings retention) {
    this.retention = retention;
  }

  @DomainEventListener(topic = "sos.society.events.v1", group = "media.society-settings",
      type = "society.settings.updated")
  public void on(CloudEvent<SettingsUpdated> e) {
    if (e.societyId() == null || e.data() == null || e.data().settings() == null) {
      return;
    }
    Integer days = e.data().settings().visitorRetentionDays();
    if (days != null) {
      retention.applyVisitorRetention(days);
    }
  }
}
