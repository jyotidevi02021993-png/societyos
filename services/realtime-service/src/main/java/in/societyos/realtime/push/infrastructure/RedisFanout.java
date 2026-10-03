package in.societyos.realtime.push.infrastructure;

import in.societyos.realtime.push.domain.Delivery;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;
import org.springframework.util.MimeTypeUtils;

/**
 * Fan-out between realtime pods (docs/architecture/04 use 7). One pod consumes a Kafka event and
 * publishes to {@code rt:user:{id}:{queue}} / {@code rt:society:{id}:{topic}}; every pod is
 * subscribed and delivers to the sockets it holds. If Redis is down the consuming pod delivers to
 * its own sockets and the notification push remains the fallback.
 */
@Component
public class RedisFanout implements MessageListener {

  private static final Logger log = LoggerFactory.getLogger(RedisFanout.class);

  private final StringRedisTemplate redis;
  private final SimpMessagingTemplate stomp;
  private final RedisMessageListenerContainer container;

  public RedisFanout(StringRedisTemplate redis, SimpMessagingTemplate stomp, RedisConnectionFactory connections) {
    this.redis = redis;
    this.stomp = stomp;
    this.container = new RedisMessageListenerContainer();
    container.setConnectionFactory(connections);
    container.addMessageListener(this, java.util.List.of(new PatternTopic("rt:user:*"), new PatternTopic("rt:society:*")));
    container.afterPropertiesSet();
    container.start();
  }

  public void publish(Delivery delivery, String payload) {
    try {
      redis.convertAndSend(delivery.redisChannel(), payload);
    } catch (RuntimeException e) {
      log.warn("Redis fan-out unavailable, delivering locally only: {}", e.getMessage());
      deliverLocally(delivery, payload);
    }
  }

  @Override
  public void onMessage(Message message, byte[] pattern) {
    try {
      Delivery delivery = Delivery.fromRedisChannel(new String(message.getChannel(), StandardCharsets.UTF_8));
      deliverLocally(delivery, new String(message.getBody(), StandardCharsets.UTF_8));
    } catch (RuntimeException e) {
      log.warn("Dropped a malformed fan-out message: {}", e.getMessage());
    }
  }

  /** Sends the JSON bytes as-is (no converter re-encoding) to this pod's broker. */
  void deliverLocally(Delivery d, String payload) {
    SimpMessageHeaderAccessor headers = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
    headers.setContentType(MimeTypeUtils.APPLICATION_JSON);
    headers.setLeaveMutable(true);
    String destination = d.kind() == Delivery.Kind.USER ? "/user/" + d.target() + "/queue/" + d.channel()
        : d.destination();
    stomp.send(destination, MessageBuilder.createMessage(payload.getBytes(StandardCharsets.UTF_8),
        headers.getMessageHeaders()));
  }

  @jakarta.annotation.PreDestroy
  void stop() {
    try {
      container.stop();
      container.destroy();
    } catch (Exception e) {
      log.debug("Redis listener stop: {}", e.getMessage());
    }
  }
}
