package in.societyos.media.platform.security;

import feign.RequestInterceptor;
import feign.RequestTemplate;
import in.societyos.media.platform.core.tenant.Tenant;
import in.societyos.media.platform.core.tenant.TenantContext;
import org.slf4j.MDC;

/** Adds {@code Authorization}, {@code X-Society-Id} and the trace id to outgoing Feign calls. */
public class TenantForwardingInterceptor implements RequestInterceptor {

  @Override
  public void apply(RequestTemplate template) {
    Tenant tenant = TenantContext.optional().orElse(null);
    if (tenant != null) {
      if (tenant.bearerToken() != null && !template.headers().containsKey("Authorization")) {
        template.header("Authorization", "Bearer " + tenant.bearerToken());
      }
      if (tenant.activeSocietyId() != null) {
        template.header(SosClaims.SOCIETY_HEADER, tenant.activeSocietyId().toString());
      }
    }
    String traceId = MDC.get("traceId");
    if (traceId != null && traceId.length() == 32) {
      template.header("traceparent", "00-" + traceId + "-" + traceId.substring(0, 16) + "-01");
    }
  }
}
