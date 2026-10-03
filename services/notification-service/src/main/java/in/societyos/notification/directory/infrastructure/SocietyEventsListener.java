package in.societyos.notification.directory.infrastructure;

import in.societyos.notification.directory.application.RecipientDirectory;
import in.societyos.notification.platform.events.CloudEvent;
import in.societyos.notification.platform.events.DomainEventListener;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Group {@code notification.society-settings} on {@code sos.society.events.v1} (DLQ
 * {@code sos.dlq.notification.society-settings}): timezone and {@code notificationRetentionDays}.
 */
@Component
class SocietyEventsListener {

  static final String TOPIC = "sos.society.events.v1";
  static final String GROUP = "notification.society-settings";

  record Settings(String timezone, Integer notificationRetentionDays) {}

  record SettingsUpdated(UUID societyId, Settings settings) {}

  record SocietyCreated(UUID societyId, String timezone) {}

  private final RecipientDirectory directory;

  SocietyEventsListener(RecipientDirectory directory) {
    this.directory = directory;
  }

  @DomainEventListener(topic = TOPIC, group = GROUP, type = "society.created")
  void onCreated(CloudEvent<SocietyCreated> e) {
    if (e.societyId() != null && e.societyId().equals(e.data().societyId())) {
      directory.settingsChanged(e.societyId(), e.data().timezone(), null);
    }
  }

  @DomainEventListener(topic = TOPIC, group = GROUP, type = "society.settings.updated")
  void onSettings(CloudEvent<SettingsUpdated> e) {
    Settings s = e.data().settings();
    if (e.societyId() != null && s != null) {
      directory.settingsChanged(e.societyId(), s.timezone(), s.notificationRetentionDays());
    }
  }
}
