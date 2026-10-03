package in.societyos.workflow.platform.events.config;

import in.societyos.workflow.platform.events.DomainEvents;
import in.societyos.workflow.platform.events.internal.DomainEventListenerRegistry;
import in.societyos.workflow.platform.events.internal.OutboxCleanup;
import in.societyos.workflow.platform.events.internal.OutboxPollingRelay;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class PlatformEventsConfig {

  @Bean
  @ConditionalOnMissingBean
  DomainEvents domainEvents(
      JdbcTemplate jdbc, JsonMapper mapper, @Value("${spring.application.name}") String source) {
    return new DomainEvents(jdbc, mapper, source);
  }

  /** Static: it is a BeanPostProcessor; its dependencies are resolved lazily. */
  @Bean
  static DomainEventListenerRegistry domainEventListenerRegistry(
      @Lazy ConsumerFactory<String, String> consumerFactory,
      @Lazy KafkaTemplate<String, String> kafkaTemplate,
      @Lazy PlatformTransactionManager txManager,
      @Lazy JdbcTemplate jdbc,
      @Lazy JsonMapper mapper,
      @Value("${sos.events.listener-concurrency:1}") int concurrency) {
    return new DomainEventListenerRegistry(
        consumerFactory, kafkaTemplate, new TransactionTemplate(txManager), jdbc, mapper, concurrency);
  }

  @Bean
  @ConditionalOnProperty(name = "sos.outbox.relay", havingValue = "polling")
  OutboxPollingRelay outboxPollingRelay(
      JdbcTemplate jdbc, KafkaTemplate<String, String> kafka, PlatformTransactionManager txManager) {
    return new OutboxPollingRelay(jdbc, kafka, new TransactionTemplate(txManager));
  }

  @Bean
  OutboxCleanup outboxCleanup(JdbcTemplate jdbc) {
    return new OutboxCleanup(jdbc);
  }
}
