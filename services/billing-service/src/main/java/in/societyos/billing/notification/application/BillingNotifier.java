package in.societyos.billing.notification.application;

import in.societyos.billing.notification.domain.NotificationRequested;
import in.societyos.billing.platform.events.DomainEvents;
import in.societyos.billing.roster.application.RosterService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Publishes {@code billing.notification.requested} to a flat's active members in the caller's
 * transaction; a flat without members with an account is skipped.
 *
 * <p>Templates: {@code billing.bill.published}, {@code billing.due.reminder},
 * {@code billing.dues.overdue}, {@code billing.latefee.applied}, {@code billing.payment.received}.
 */
@Service
public class BillingNotifier {

  public static final String BILL_PUBLISHED = "billing.bill.published";
  public static final String DUE_REMINDER = "billing.due.reminder";
  public static final String OVERDUE = "billing.dues.overdue";
  public static final String LATE_FEE = "billing.latefee.applied";
  public static final String PAYMENT_RECEIVED = "billing.payment.received";

  private final DomainEvents events;
  private final RosterService roster;

  public BillingNotifier(DomainEvents events, RosterService roster) {
    this.events = events;
    this.roster = roster;
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void toFlat(UUID flatId, String template, Map<String, String> params, String dedupeKey, boolean urgent) {
    List<UUID> recipients = roster.recipientsOf(flatId);
    if (recipients.isEmpty()) {
      return;
    }
    List<String> channels = urgent ? List.of("PUSH", "WHATSAPP", "INAPP") : List.of("PUSH", "INAPP");
    events.publish(new NotificationRequested(recipients, NotificationRequested.BILLING, template, params, channels,
        urgent ? "HIGH" : "NORMAL", dedupeKey));
  }
}
