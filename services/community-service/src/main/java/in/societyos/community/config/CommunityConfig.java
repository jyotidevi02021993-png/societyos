package in.societyos.community.config;

import java.time.Clock;
import java.time.ZoneId;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class CommunityConfig {

  @Bean
  @ConditionalOnMissingBean
  Clock clock() {
    return Clock.systemUTC();
  }

  /** Society-local zone for booking slot grids and weekly limits (IST for the pilot). */
  @Bean
  ZoneId societyZone(@Value("${sos.default-timezone:Asia/Kolkata}") String zone) {
    return ZoneId.of(zone);
  }
}
