package in.societyos.asset.alerts.application;

import in.societyos.asset.alerts.domain.AlertRecipient;
import in.societyos.asset.alerts.infrastructure.AlertRecipientRepository;
import in.societyos.asset.common.NotificationRequested;
import in.societyos.asset.platform.core.UuidV7;
import in.societyos.asset.platform.events.DomainEvents;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Who gets asset alerts, and publishing {@code asset.notification.requested}. */
@Service
public class AlertService {

  /** Roles notification-service resolves when a society has no configured recipients. */
  static final List<String> FALLBACK_ROLES = List.of("ESTATE_MANAGER", "FACILITY_MANAGER");

  private final AlertRecipientRepository recipients;
  private final DomainEvents events;

  public AlertService(AlertRecipientRepository recipients, DomainEvents events) {
    this.recipients = recipients;
    this.events = events;
  }

  @Transactional(readOnly = true)
  public List<UUID> recipients() {
    return recipients.findAllByOrderByCreatedAtAsc().stream().map(AlertRecipient::getUserId).toList();
  }

  @Transactional
  public List<UUID> replaceRecipients(List<UUID> userIds) {
    Set<UUID> wanted = new LinkedHashSet<>(userIds == null ? List.of() : userIds);
    wanted.remove(null);
    List<AlertRecipient> current = recipients.findAllByOrderByCreatedAtAsc();
    for (AlertRecipient r : current) {
      if (!wanted.remove(r.getUserId())) {
        recipients.delete(r);
      }
    }
    wanted.forEach(u -> recipients.save(new AlertRecipient(u)));
    recipients.flush();
    return recipients();
  }

  /**
   * Publishes a notification request to the configured recipients plus {@code extraUsers}
   * (e.g. the PM assignee). Must run inside the caller's transaction.
   */
  public void notify(String template, Map<String, String> params, String priority, String dedupeKey,
      UUID... extraUsers) {
    Set<UUID> to = new LinkedHashSet<>(recipients());
    for (UUID u : extraUsers) {
      if (u != null) {
        to.add(u);
      }
    }
    List<String> roles = to.isEmpty() ? FALLBACK_ROLES : List.of();
    List<String> channels = new ArrayList<>(List.of("PUSH", "INAPP"));
    if ("HIGH".equals(priority)) {
      channels.add("WHATSAPP");
    }
    Map<String, String> cleanParams = new java.util.LinkedHashMap<>();
    params.forEach((k, v) -> cleanParams.put(k, Objects.toString(v, "")));
    events.publish(new NotificationRequested(UuidV7.next(), List.copyOf(to), roles, "ALERT", template,
        cleanParams, channels, priority, dedupeKey));
  }
}
