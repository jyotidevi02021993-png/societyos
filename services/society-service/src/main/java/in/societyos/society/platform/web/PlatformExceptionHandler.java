package in.societyos.society.platform.web;

import in.societyos.society.platform.core.error.ProblemException;
import in.societyos.society.platform.core.tenant.NoTenantException;
import feign.FeignException;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.cloud.client.circuitbreaker.NoFallbackAvailableException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Renders every error as {@code application/problem+json} with a stable {@code code} and the
 * {@code traceId}, so a support ticket can be matched to logs and traces.
 */
@RestControllerAdvice
public class PlatformExceptionHandler extends ResponseEntityExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(PlatformExceptionHandler.class);

  @ExceptionHandler(ProblemException.class)
  ResponseEntity<ProblemDetail> problem(ProblemException ex) {
    ProblemDetail pd = detail(ex.status(), ex.code(), ex.getMessage());
    ex.details().forEach(pd::setProperty);
    return ResponseEntity.status(ex.status()).body(pd);
  }

  @ExceptionHandler(OptimisticLockingFailureException.class)
  ResponseEntity<ProblemDetail> optimisticLock(OptimisticLockingFailureException ex) {
    return ResponseEntity.status(HttpStatus.CONFLICT)
        .body(detail(HttpStatus.CONFLICT, "VERSION_CONFLICT", "The record was changed by someone else; reload and retry"));
  }

  @ExceptionHandler(DataIntegrityViolationException.class)
  ResponseEntity<ProblemDetail> integrity(DataIntegrityViolationException ex) {
    String msg = ex.getMostSpecificCause().getMessage();
    if (msg != null && msg.contains("RECORD_LOCKED")) {
      return ResponseEntity.status(HttpStatus.UNPROCESSABLE_CONTENT)
          .body(detail(HttpStatus.UNPROCESSABLE_CONTENT, "RECORD_LOCKED", "The record is locked; use a reversal"));
    }
    log.warn("Data integrity violation: {}", msg);
    return ResponseEntity.status(HttpStatus.CONFLICT)
        .body(detail(HttpStatus.CONFLICT, "DATA_CONFLICT", "The request conflicts with existing data"));
  }

  /** A downstream service (Feign) refused or failed: never leak its body, keep the status family. */
  @ExceptionHandler(FeignException.class)
  ResponseEntity<ProblemDetail> feign(FeignException ex) {
    int status = ex.status();
    if (status == 401 || status == 403) {
      return ResponseEntity.status(status)
          .body(detail(HttpStatus.valueOf(status), "UPSTREAM_DENIED", "A dependent service refused the request"));
    }
    if (status >= 400 && status < 500) {
      return ResponseEntity.status(HttpStatus.UNPROCESSABLE_CONTENT)
          .body(detail(HttpStatus.UNPROCESSABLE_CONTENT, "UPSTREAM_REJECTED", "A dependent service rejected the request"));
    }
    log.warn("Dependency failure: {}", ex.getMessage());
    return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
        .body(detail(HttpStatus.SERVICE_UNAVAILABLE, "DEPENDENCY_UNAVAILABLE", "A dependent service is unavailable, please retry"));
  }

  /** Circuit breaker open or dependency timed out. */
  @ExceptionHandler(NoFallbackAvailableException.class)
  ResponseEntity<ProblemDetail> noFallback(NoFallbackAvailableException ex) {
    if (ex.getCause() instanceof FeignException fe) {
      return feign(fe);
    }
    return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
        .body(detail(HttpStatus.SERVICE_UNAVAILABLE, "DEPENDENCY_UNAVAILABLE", "A dependent service is unavailable, please retry"));
  }

  @ExceptionHandler(NoTenantException.class)
  ResponseEntity<ProblemDetail> noTenant(NoTenantException ex) {
    return ResponseEntity.badRequest()
        .body(detail(HttpStatus.BAD_REQUEST, "SOCIETY_REQUIRED", "Select a society (X-Society-Id) for this request"));
  }

  @Override
  protected ResponseEntity<Object> handleMethodArgumentNotValid(
      MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
    ProblemDetail pd = detail(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Request validation failed");
    List<Map<String, Object>> errors =
        ex.getBindingResult().getFieldErrors().stream()
            .map(
                fe -> {
                  Map<String, Object> m = new LinkedHashMap<>();
                  m.put("field", fe.getField());
                  m.put("message", fe.getDefaultMessage());
                  return m;
                })
            .toList();
    pd.setProperty("errors", errors);
    return ResponseEntity.badRequest().body(pd);
  }

  @Override
  protected ResponseEntity<Object> createResponseEntity(
      Object body, HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
    // Framework errors (404, 405, unreadable body...) get the same code/traceId fields.
    if (body instanceof ProblemDetail pd
        && (pd.getProperties() == null || !pd.getProperties().containsKey("code"))) {
      pd.setProperty("code", "HTTP_" + statusCode.value());
      pd.setProperty("traceId", MDC.get(CorrelationFilter.MDC_TRACE_ID));
    }
    return super.createResponseEntity(body, headers, statusCode, request);
  }

  static ProblemDetail detail(HttpStatusCode status, String code, String message) {
    ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, message);
    pd.setType(URI.create("https://docs.societyos.in/errors/" + code));
    pd.setTitle(code);
    pd.setProperty("code", code);
    pd.setProperty("traceId", MDC.get(CorrelationFilter.MDC_TRACE_ID));
    return pd;
  }
}
