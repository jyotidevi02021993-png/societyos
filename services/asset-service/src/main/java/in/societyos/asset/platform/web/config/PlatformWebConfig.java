package in.societyos.asset.platform.web.config;

import in.societyos.asset.platform.web.CorrelationFilter;
import in.societyos.asset.platform.web.IdempotencyFilter;
import in.societyos.asset.platform.web.PlatformExceptionHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.json.JsonMapper;

@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class PlatformWebConfig {

  @Bean
  PlatformExceptionHandler platformExceptionHandler() {
    return new PlatformExceptionHandler();
  }

  /** First filter: the trace id must exist before security logs anything. */
  @Bean
  FilterRegistrationBean<CorrelationFilter> correlationFilter() {
    var reg = new FilterRegistrationBean<>(new CorrelationFilter());
    reg.setOrder(Ordered.HIGHEST_PRECEDENCE);
    return reg;
  }

  /** After Spring Security (order -100), so the user id is known. */
  @Bean
  @ConditionalOnProperty(name = "sos.web.idempotency.enabled", havingValue = "true", matchIfMissing = true)
  FilterRegistrationBean<IdempotencyFilter> idempotencyFilter(
      StringRedisTemplate redis, JsonMapper mapper, @Value("${spring.application.name}") String service) {
    var reg = new FilterRegistrationBean<>(new IdempotencyFilter(redis, mapper, service));
    reg.setOrder(0);
    return reg;
  }
}
