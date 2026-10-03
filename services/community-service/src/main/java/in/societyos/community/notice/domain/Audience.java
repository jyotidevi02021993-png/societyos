package in.societyos.community.notice.domain;

import in.societyos.community.platform.core.error.ProblemException;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Who a notice is for: everyone, residents of some towers, or holders of some roles. Towers and
 * roles may be combined (either matches).
 */
public record Audience(boolean all, List<UUID> towerIds, List<String> roles) {

  public Audience {
    towerIds = towerIds == null ? List.of() : List.copyOf(towerIds);
    roles = roles == null ? List.of() : roles.stream().map(r -> r.trim().toUpperCase()).distinct().toList();
  }

  public static Audience everyone() {
    return new Audience(true, List.of(), List.of());
  }

  public Audience validated() {
    if (!all && towerIds.isEmpty() && roles.isEmpty()) {
      throw ProblemException.badRequest("INVALID_AUDIENCE", "Target everyone, some towers or some roles");
    }
    return all ? everyone() : this;
  }

  /** Whether a reader living in {@code myTowers} with {@code myRoles} is addressed. */
  public boolean matches(Collection<UUID> myTowers, Set<String> myRoles) {
    return all
        || towerIds.stream().anyMatch(myTowers::contains)
        || roles.stream().anyMatch(myRoles::contains);
  }
}
