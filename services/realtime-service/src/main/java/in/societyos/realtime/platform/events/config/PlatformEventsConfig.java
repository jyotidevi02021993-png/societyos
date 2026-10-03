package in.societyos.realtime.platform.events.config;

import in.societyos.realtime.platform.events.internal.DomainEventListenerRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * realtime-service variant of platform-events: no database, so no outbox (it publishes nothing)
 * and no inbox table. Listeners are deduplicated in Redis instead ({@code rt:seen:*}, 1 h TTL);
 * a lost dedupe key only means a push may repeat, which clients ignore by event id.
 */
@Configuration(proxyBeanMethods = false)
public class PlatformEventsConfig {

  /** Static: it is a BeanPostProcessor; its dependencies are resolved lazily. */
  @Bean
  static DomainEventListenerRegistry domainEventListenerRegistry(
      @Lazy ConsumerFactory<String, String> consumerFactory,
      @Lazy KafkaTemplate<String, String> kafkaTemplate,
      @Lazy StringRedisTemplate redis,
      @Lazy JsonMapper mapper,
      @Value("${sos.events.listener-concurrency:1}") int concurrency) {
    return new DomainEventListenerRegistry(consumerFactory, kafkaTemplate, redis, mapper, concurrency);
  }
}
