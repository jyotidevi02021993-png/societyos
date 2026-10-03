package in.societyos.media.media.application;

import in.societyos.media.media.domain.MediaPurpose;
import in.societyos.media.media.domain.MediaSocietySettings;
import in.societyos.media.media.infrastructure.MediaSocietySettingsRepository;
import java.time.Duration;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Per-society retention: visitor photos follow the society's visitorRetentionDays, others their purpose default. */
@Service
public class RetentionSettings {

  private final MediaSocietySettingsRepository settings;

  public RetentionSettings(MediaSocietySettingsRepository settings) {
    this.settings = settings;
  }

  /** Called from the society.settings.updated consumer, tenant bound to that society. */
  @Transactional
  public void applyVisitorRetention(int days) {
    if (days <= 0) {
      return;
    }
    MediaSocietySettings s = settings.findFirstBy().orElseGet(() -> new MediaSocietySettings(days));
    s.setVisitorRetentionDays(days);
    settings.save(s);
  }

  /** Retention for a new upload in the bound society; null means keep until deleted. */
  @Transactional(readOnly = true)
  public Duration retentionFor(MediaPurpose purpose) {
    if (purpose == MediaPurpose.VISITOR_PHOTO) {
      return settings.findFirstBy()
          .map(s -> Duration.ofDays(s.getVisitorRetentionDays()))
          .orElse(purpose.defaultRetention());
    }
    return purpose.defaultRetention();
  }
}
