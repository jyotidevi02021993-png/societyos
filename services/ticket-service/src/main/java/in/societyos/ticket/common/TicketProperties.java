package in.societyos.ticket.common;

import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code sos.ticket.*}.
 *
 * @param resolveMinsByPriority provisional SLA shown to the resident when the category has none;
 *     the authoritative timer runs in workflow-service
 * @param jobcardApprovalThresholdPaise completed job cards costing more than this wait for a
 *     workflow approval before they can be verified
 * @param reopenWindowDays how long after closing a resident may reopen a complaint
 */
@ConfigurationProperties("sos.ticket")
public record TicketProperties(
    Map<String, Integer> resolveMinsByPriority, Long jobcardApprovalThresholdPaise, Integer reopenWindowDays) {

  public TicketProperties {
    if (resolveMinsByPriority == null || resolveMinsByPriority.isEmpty()) {
      resolveMinsByPriority = Map.of("P1", 240, "P2", 1440, "P3", 4320, "P4", 10080);
    }
    if (jobcardApprovalThresholdPaise == null) {
      jobcardApprovalThresholdPaise = 500_000L;
    }
    if (reopenWindowDays == null) {
      reopenWindowDays = 7;
    }
  }

  public int resolveMins(String priority) {
    return resolveMinsByPriority.getOrDefault(priority, 4320);
  }
}
