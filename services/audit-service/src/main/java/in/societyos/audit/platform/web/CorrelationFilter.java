package in.societyos.audit.platform.web;

import in.societyos.audit.platform.core.UuidV7;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Puts a trace id in the MDC for every request (from W3C {@code traceparent} when the gateway
 * sent one, otherwise generated) and echoes it as {@code X-Trace-Id}.
 */
public class CorrelationFilter extends OncePerRequestFilter {

  public static final String MDC_TRACE_ID = "traceId";
  public static final String TRACE_HEADER = "X-Trace-Id";

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String traceId = traceIdFrom(request.getHeader("traceparent"));
    if (traceId == null) {
      traceId = UuidV7.next().toString().replace("-", "");
    }
    MDC.put(MDC_TRACE_ID, traceId);
    response.setHeader(TRACE_HEADER, traceId);
    try {
      chain.doFilter(request, response);
    } finally {
      MDC.remove(MDC_TRACE_ID);
    }
  }

  /** {@code 00-<32 hex trace id>-<16 hex span id>-<flags>} */
  static String traceIdFrom(String traceparent) {
    if (traceparent == null) {
      return null;
    }
    String[] parts = traceparent.split("-");
    return parts.length == 4 && parts[1].length() == 32 ? parts[1] : null;
  }
}
