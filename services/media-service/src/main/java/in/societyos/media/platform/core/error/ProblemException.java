package in.societyos.media.platform.core.error;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;

/**
 * A business error with a stable machine-readable {@code code} (e.g. {@code JOBCARD_LOCKED}).
 * Rendered as RFC 7807 {@code application/problem+json} by the platform exception handler.
 */
public class ProblemException extends RuntimeException {

  private final String code;
  private final HttpStatusCode status;
  private final transient Map<String, Object> details;

  public ProblemException(String code, HttpStatusCode status, String message) {
    this(code, status, message, Map.of());
  }

  public ProblemException(
      String code, HttpStatusCode status, String message, Map<String, Object> details) {
    super(message);
    this.code = code;
    this.status = status;
    this.details = Map.copyOf(details);
  }

  public static ProblemException notFound(String entity, Object id) {
    return new ProblemException(
        entity.toUpperCase() + "_NOT_FOUND", HttpStatus.NOT_FOUND, entity + " " + id + " not found");
  }

  public static ProblemException conflict(String code, String message) {
    return new ProblemException(code, HttpStatus.CONFLICT, message);
  }

  public static ProblemException badRequest(String code, String message) {
    return new ProblemException(code, HttpStatus.BAD_REQUEST, message);
  }

  public static ProblemException forbidden(String code, String message) {
    return new ProblemException(code, HttpStatus.FORBIDDEN, message);
  }

  /** Rule violation on a valid request (state machine, locked record). */
  public static ProblemException unprocessable(String code, String message) {
    return new ProblemException(code, HttpStatus.UNPROCESSABLE_CONTENT, message);
  }

  public String code() {
    return code;
  }

  public HttpStatusCode status() {
    return status;
  }

  public Map<String, Object> details() {
    return details;
  }
}
