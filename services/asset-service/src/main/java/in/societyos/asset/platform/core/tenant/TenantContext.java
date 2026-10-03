package in.societyos.asset.platform.core.tenant;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;

/**
 * The tenant of the current thread. Set by the web filter from the JWT, by event listeners
 * from the event's {@code societyid}, and by scheduled jobs per society. Always cleared at the
 * end of the unit of work; {@link #runAs} and {@link #callAs} restore the previous value.
 */
public final class TenantContext {

  private static final ThreadLocal<Tenant> CURRENT = new ThreadLocal<>();

  private TenantContext() {}

  public static Tenant current() {
    Tenant t = CURRENT.get();
    if (t == null) {
      throw new NoTenantException("No tenant bound to this thread");
    }
    return t;
  }

  public static Optional<Tenant> optional() {
    return Optional.ofNullable(CURRENT.get());
  }

  public static UUID activeSocietyId() {
    return current().requireActiveSociety();
  }

  public static Optional<UUID> userId() {
    return optional().map(Tenant::userId);
  }

  public static void set(Tenant tenant) {
    CURRENT.set(tenant);
  }

  public static void clear() {
    CURRENT.remove();
  }

  public static void runAs(Tenant tenant, Runnable work) {
    Tenant previous = CURRENT.get();
    CURRENT.set(tenant);
    try {
      work.run();
    } finally {
      restore(previous);
    }
  }

  public static <T> T callAs(Tenant tenant, Callable<T> work) throws Exception {
    Tenant previous = CURRENT.get();
    CURRENT.set(tenant);
    try {
      return work.call();
    } finally {
      restore(previous);
    }
  }

  /** Runs as the system actor inside one society. */
  public static void runAs(UUID societyId, Runnable work) {
    runAs(Tenant.system(societyId), work);
  }

  private static void restore(Tenant previous) {
    if (previous == null) {
      CURRENT.remove();
    } else {
      CURRENT.set(previous);
    }
  }
}
