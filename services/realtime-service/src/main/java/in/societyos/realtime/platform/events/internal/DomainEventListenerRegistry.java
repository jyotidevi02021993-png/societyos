package in.societyos.realtime.platform.events.internal;

import in.societyos.realtime.platform.core.tenant.Tenant;
import in.societyos.realtime.platform.core.tenant.TenantContext;
import in.societyos.realtime.platform.events.CloudEvent;
import in.societyos.realtime.platform.events.DomainEventListener;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.SmartLifecycle;
import org.springframework.core.MethodIntrospector;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.util.backoff.ExponentialBackOff;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Finds {@link DomainEventListener} methods and runs one Kafka listener container per
 * (topic, group). Each record: filter by type → bind tenant → Redis dedupe ({@code rt:seen:*},
 * this service has no database) → handler → commit offset. Unknown types are skipped, not failed, so producers can add event types freely.
 */
public class DomainEventListenerRegistry implements BeanPostProcessor, SmartLifecycle {

  private static final Logger log = LoggerFactory.getLogger(DomainEventListenerRegistry.class);
  private static final Duration SEEN_TTL = Duration.ofHours(1);

  private final List<Handler> handlers = new ArrayList<>();
  private final List<ConcurrentMessageListenerContainer<String, String>> containers = new ArrayList<>();

  private final ConsumerFactory<String, String> consumerFactory;
  private final KafkaTemplate<String, String> kafkaTemplate;
  private final StringRedisTemplate redis;
  private final JsonMapper mapper;
  private final int concurrency;
  private volatile boolean running;

  public DomainEventListenerRegistry(
      ConsumerFactory<String, String> consumerFactory,
      KafkaTemplate<String, String> kafkaTemplate,
      StringRedisTemplate redis,
      JsonMapper mapper,
      int concurrency) {
    this.consumerFactory = consumerFactory;
    this.kafkaTemplate = kafkaTemplate;
    this.redis = redis;
    this.mapper = mapper;
    this.concurrency = concurrency;
  }

  record Handler(
      Object bean, Method method, String topic, String group, List<String> types, JavaType dataType) {
    boolean accepts(String type) {
      return types.isEmpty() || types.contains(type);
    }
  }

  @Override
  public Object postProcessAfterInitialization(Object bean, String beanName) {
    Class<?> targetClass = AopUtils.getTargetClass(bean);
    Map<Method, DomainEventListener> found =
        MethodIntrospector.selectMethods(
            targetClass,
            (MethodIntrospector.MetadataLookup<DomainEventListener>)
                m -> AnnotatedElementUtils.findMergedAnnotation(m, DomainEventListener.class));
    found.forEach(
        (method, ann) -> {
          if (method.getParameterCount() != 1 || method.getParameterTypes()[0] != CloudEvent.class) {
            throw new IllegalStateException(
                "@DomainEventListener method must take one CloudEvent<T> parameter: " + method);
          }
          Method invocable = AopUtils.selectInvocableMethod(method, bean.getClass());
          invocable.setAccessible(true);
          handlers.add(
              new Handler(
                  bean,
                  invocable,
                  ann.topic(),
                  ann.group(),
                  List.of(ann.type()),
                  dataType(method.getGenericParameterTypes()[0])));
        });
    return bean;
  }

  private JavaType dataType(Type cloudEventType) {
    if (cloudEventType instanceof ParameterizedType p) {
      return mapper.getTypeFactory().constructType(p.getActualTypeArguments()[0]);
    }
    return mapper.getTypeFactory().constructType(JsonNode.class);
  }

  @Override
  public void start() {
    Map<String, List<Handler>> byContainer = new LinkedHashMap<>();
    for (Handler h : handlers) {
      byContainer.computeIfAbsent(h.topic() + "|" + h.group(), k -> new ArrayList<>()).add(h);
    }
    byContainer.forEach(
        (key, hs) -> {
          String topic = hs.getFirst().topic();
          String group = hs.getFirst().group();
          ContainerProperties props = new ContainerProperties(topic);
          props.setGroupId(group);
          props.setAckMode(ContainerProperties.AckMode.RECORD);
          props.setMessageListener(
              (MessageListener<String, String>) rec -> dispatch(group, hs, rec));
          var container = new ConcurrentMessageListenerContainer<>(consumerFactory, props);
          container.setConcurrency(concurrency);
          container.setCommonErrorHandler(errorHandler(group));
          container.setBeanName("sos-" + group + "-" + topic);
          container.start();
          containers.add(container);
          log.info("Listening to {} as {} for {}", topic, group, hs.stream().flatMap(h -> h.types().stream()).toList());
        });
    running = true;
  }

  private DefaultErrorHandler errorHandler(String group) {
    var recoverer =
        new DeadLetterPublishingRecoverer(
            kafkaTemplate, (rec, ex) -> new TopicPartition("sos.dlq." + group, -1));
    var backOff = new ExponentialBackOff(1000, 2.0);
    backOff.setMaxAttempts(3);
    var handler = new DefaultErrorHandler(recoverer, backOff);
    handler.addNotRetryableExceptions(JacksonException.class, IllegalArgumentException.class);
    return handler;
  }

  void dispatch(String group, List<Handler> candidates, ConsumerRecord<String, String> rec) {
    JsonNode envelope = mapper.readTree(rec.value());
    String type = header(rec, "ce_type");
    if (type == null) {
      type = envelope.path("type").asString();
    }
    final String eventType = type;
    List<Handler> matching = candidates.stream().filter(h -> h.accepts(eventType)).toList();
    if (matching.isEmpty()) {
      return; // unknown or uninteresting type: skip, never fail
    }
    UUID eventId = UUID.fromString(envelope.path("id").asString());
    UUID societyId = uuidOrNull(envelope.path("societyid"));
    Tenant tenant = societyId == null ? Tenant.platform() : Tenant.system(societyId);

    MDC.put("societyId", societyId == null ? "-" : societyId.toString());
    MDC.put("eventId", eventId.toString());
    try {
      TenantContext.runAs(
          tenant,
          () -> {
            String key = "rt:seen:" + group + ":" + eventId;
            if (!firstDelivery(key)) {
              log.debug("Duplicate event {} for {}, skipped", eventId, group);
              return;
            }
            try {
              for (Handler h : matching) {
                invoke(h, toCloudEvent(envelope, eventType, societyId, eventId, h.dataType()));
              }
            } catch (RuntimeException e) {
              forget(key); // let the retry run the handler again
              throw e;
            }
          });
    } finally {
      MDC.remove("societyId");
      MDC.remove("eventId");
    }
  }

  /** Fails open: without Redis a redelivered event may be pushed twice, never dropped. */
  private boolean firstDelivery(String key) {
    try {
      return !Boolean.FALSE.equals(redis.opsForValue().setIfAbsent(key, "1", SEEN_TTL));
    } catch (RuntimeException e) {
      log.warn("Dedupe store unavailable: {}", e.getMessage());
      return true;
    }
  }

  private void forget(String key) {
    try {
      redis.delete(key);
    } catch (RuntimeException ignored) {
      // the key expires on its own
    }
  }

  private CloudEvent<Object> toCloudEvent(
      JsonNode env, String type, UUID societyId, UUID eventId, JavaType dataType) {
    Object data = mapper.convertValue(env.path("data"), dataType);
    String time = env.path("time").asString();
    return new CloudEvent<>(
        eventId,
        env.path("source").asString(),
        type,
        time.isEmpty() ? null : Instant.parse(time),
        env.path("subject").asString(),
        societyId,
        uuidOrNull(env.path("actorid")),
        env.path("actortype").asString(),
        data);
  }

  private static void invoke(Handler h, CloudEvent<Object> event) {
    try {
      h.method().invoke(h.bean(), event);
    } catch (InvocationTargetException e) {
      if (e.getCause() instanceof RuntimeException re) {
        throw re;
      }
      throw new IllegalStateException(e.getCause());
    } catch (IllegalAccessException e) {
      throw new IllegalStateException(e);
    }
  }

  private static UUID uuidOrNull(JsonNode node) {
    return node == null || node.isNull() || node.isMissingNode() || node.asString().isEmpty()
        ? null
        : UUID.fromString(node.asString());
  }

  private static String header(ConsumerRecord<String, String> rec, String name) {
    Header h = rec.headers().lastHeader(name);
    return h == null ? null : new String(h.value(), StandardCharsets.UTF_8);
  }

  @Override
  public void stop() {
    containers.forEach(ConcurrentMessageListenerContainer::stop);
    containers.clear();
    running = false;
  }

  @Override
  public boolean isRunning() {
    return running;
  }

  /** Start after the web server and other beans; stop consuming first on shutdown. */
  @Override
  public int getPhase() {
    return Integer.MAX_VALUE - 100;
  }

  public List<String> registeredGroups() {
    return handlers.stream().map(Handler::group).distinct().toList();
  }
}
