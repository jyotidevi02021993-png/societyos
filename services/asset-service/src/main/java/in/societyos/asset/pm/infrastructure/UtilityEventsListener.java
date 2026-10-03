package in.societyos.asset.pm.infrastructure;

import in.societyos.asset.platform.events.CloudEvent;
import in.societyos.asset.platform.events.DomainEventListener;
import in.societyos.asset.pm.application.PmTaskService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Usage-based PM (doc 06 flow 3): {@code utility.reading.recorded} for an asset and metric,
 * e.g. DG running hours. DLQ: {@code sos.dlq.asset.usage-pm}.
 */
@Component
class UtilityEventsListener {

  static final String TOPIC = "sos.utility.events.v1";
  static final String GROUP = "asset.usage-pm";

  record ReadingRecorded(UUID readingId, UUID assetId, UUID meterId, String metric, BigDecimal value, String unit,
      Instant at) {}

  private final PmTaskService pmTasks;

  UtilityEventsListener(PmTaskService pmTasks) {
    this.pmTasks = pmTasks;
  }

  @DomainEventListener(topic = TOPIC, group = GROUP, type = "utility.reading.recorded")
  void onReading(CloudEvent<ReadingRecorded> event) {
    ReadingRecorded r = event.data();
    pmTasks.onUsageReading(r.assetId(), r.metric(), r.value());
  }
}
