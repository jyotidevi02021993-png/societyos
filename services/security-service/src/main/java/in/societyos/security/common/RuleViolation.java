package in.societyos.security.common;

import in.societyos.security.platform.core.error.ProblemException;
import org.springframework.http.HttpStatus;

/** A domain rule was broken (state machine, window, limit): rendered as HTTP 422 with {@code code}. */
public class RuleViolation extends ProblemException {

  public RuleViolation(String code, String message) {
    super(code, HttpStatus.UNPROCESSABLE_CONTENT, message);
  }
}
