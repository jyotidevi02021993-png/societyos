package in.societyos.compliance.platform.jpa.config;

import in.societyos.compliance.platform.jpa.DocumentNumberService;
import in.societyos.compliance.platform.jpa.TenantAwareJpaTransactionManager;
import jakarta.persistence.EntityManagerFactory;
import java.time.Clock;
import java.time.ZoneId;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(EntityManagerFactory.class)
public class PlatformJpaConfig {

  /** Replaces Boot's JpaTransactionManager so every transaction carries the RLS tenant. */
  @Bean(name = "transactionManager")
  PlatformTransactionManager transactionManager(EntityManagerFactory emf) {
    return new TenantAwareJpaTransactionManager(emf);
  }

  @Bean
  @ConditionalOnMissingBean
  Clock clock() {
    return Clock.systemUTC();
  }

  @Bean
  @ConditionalOnMissingBean
  DocumentNumberService documentNumberService(
      JdbcTemplate jdbc, Clock clock, @Value("${sos.default-timezone:Asia/Kolkata}") String zone) {
    return new DocumentNumberService(jdbc, clock, ZoneId.of(zone));
  }
}
