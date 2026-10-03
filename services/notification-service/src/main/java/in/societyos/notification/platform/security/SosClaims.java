package in.societyos.notification.platform.security;

/** Custom JWT claims issued by identity-service (see docs/architecture/05 §2). */
public final class SosClaims {

  /** Active society: writes go here. */
  public static final String SOCIETY = "sid";
  /** Readable societies (multi-site FM users). */
  public static final String SOCIETIES = "sids";
  public static final String ROLES = "roles";
  /** Permission version: part of the permission cache key. */
  public static final String PERMISSION_VERSION = "pv";
  public static final String DEVICE = "did";
  /** USER or SERVICE */
  public static final String ACTOR_TYPE = "typ";

  public static final String SOCIETY_HEADER = "X-Society-Id";

  private SosClaims() {}
}
