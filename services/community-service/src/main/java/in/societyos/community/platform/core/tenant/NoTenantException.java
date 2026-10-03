package in.societyos.community.platform.core.tenant;

/** Thrown when tenant-scoped work runs without a tenant. Always a programming error. */
public class NoTenantException extends IllegalStateException {
  public NoTenantException(String message) {
    super(message);
  }
}
