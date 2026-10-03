package in.societyos.realtime.push.domain;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Who sees which gate and security event (docs/architecture/01 §10):
 *
 * <ul>
 *   <li>{@code security.entry.requested}: every resident asked ({@code /user/queue/gate}) and the
 *       guard console ({@code /topic/society.{id}.gate}).
 *   <li>decisions, expiry, check-in/out: the guard console, and the residents who were asked so
 *       their other devices dismiss the prompt.
 *   <li>{@code security.sos.raised}: guard console and manager dashboard ({@code .alerts}).
 *   <li>{@code security.incident.reported}: manager dashboard.
 * </ul>
 */
public final class PushRouter {

  public static final String GATE = "gate";
  public static final String ALERTS = "alerts";

  public static final Set<String> ENTRY_FOLLOW_UPS = Set.of("security.entry.approved", "security.entry.denied",
      "security.entry.expired", "security.entry.checked_in", "security.entry.checked_out");

  private PushRouter() {}

  /**
   * @param residents the residents asked for this entry: from the event for a request, or
   *     remembered from the request for its follow-ups (may be empty)
   */
  public static List<Delivery> route(String type, UUID societyId, Collection<UUID> residents) {
    if (societyId == null || type == null) {
      return List.of();
    }
    List<Delivery> out = new ArrayList<>();
    if ("security.entry.requested".equals(type) || ENTRY_FOLLOW_UPS.contains(type)) {
      new LinkedHashSet<>(residents).forEach(r -> out.add(Delivery.user(r, GATE)));
      out.add(Delivery.society(societyId, GATE));
    } else if ("security.sos.raised".equals(type)) {
      out.add(Delivery.society(societyId, GATE));
      out.add(Delivery.society(societyId, ALERTS));
    } else if ("security.incident.reported".equals(type)) {
      out.add(Delivery.society(societyId, ALERTS));
    }
    return out;
  }
}
