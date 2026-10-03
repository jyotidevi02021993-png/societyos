package in.societyos.society.member.infrastructure;

import feign.FeignException;
import in.societyos.society.member.application.UserDirectory;
import in.societyos.society.platform.core.error.ProblemException;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** {@link UserDirectory} over OpenFeign; identity errors become problems the caller can act on. */
@Component
class IdentityUserDirectory implements UserDirectory {

  private static final Logger log = LoggerFactory.getLogger(IdentityUserDirectory.class);

  private final IdentityUsersClient identity;

  IdentityUserDirectory(IdentityUsersClient identity) {
    this.identity = identity;
  }

  @Override
  public UUID resolveByPhone(String phone, String name) {
    try {
      return identity.resolve(new IdentityUsersClient.ResolveRequest(phone, name)).userId();
    } catch (RuntimeException e) {
      FeignException feign = feignCause(e);
      int status = feign == null ? -1 : feign.status();
      if (status == 400) {
        throw ProblemException.badRequest("INVALID_PHONE", "identity-service rejected the phone number");
      }
      if (status == 401 || status == 403) {
        throw ProblemException.forbidden("IDENTITY_FORBIDDEN", "You are not allowed to add people in identity-service");
      }
      log.warn("identity-service user resolve failed (status {})", status, e);
      throw new ProblemException("IDENTITY_UNAVAILABLE", HttpStatus.SERVICE_UNAVAILABLE,
          "identity-service is unavailable; try again shortly");
    }
  }

  /** The circuit breaker wraps Feign errors; find the original one. */
  private static FeignException feignCause(Throwable e) {
    for (Throwable t = e; t != null; t = t.getCause()) {
      if (t instanceof FeignException f) {
        return f;
      }
    }
    return null;
  }
}
