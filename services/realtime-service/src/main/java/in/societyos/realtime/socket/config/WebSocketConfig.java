package in.societyos.realtime.socket.config;

import in.societyos.realtime.socket.application.StompAuthInterceptor;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * STOMP over WebSocket at {@code /ws} (SockJS fallback at {@code /ws-sockjs}). The in-memory
 * broker serves this pod's sockets only; pods share events through Redis pub/sub.
 * Destinations: {@code /user/queue/gate}, {@code /topic/society.{id}.gate}, {@code /topic/society.{id}.alerts}.
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSocketMessageBroker
@EnableScheduling
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

  private final StompAuthInterceptor auth;
  private final List<String> allowedOrigins;

  public WebSocketConfig(StompAuthInterceptor auth,
      @Value("${sos.realtime.allowed-origins:*}") List<String> allowedOrigins) {
    this.auth = auth;
    this.allowedOrigins = allowedOrigins;
  }

  @Override
  public void registerStompEndpoints(StompEndpointRegistry registry) {
    String[] origins = allowedOrigins.toArray(String[]::new);
    registry.addEndpoint("/ws").setAllowedOriginPatterns(origins);
    registry.addEndpoint("/ws-sockjs").setAllowedOriginPatterns(origins).withSockJS();
  }

  @Override
  public void configureMessageBroker(MessageBrokerRegistry registry) {
    ThreadPoolTaskScheduler heartbeat = new ThreadPoolTaskScheduler();
    heartbeat.setPoolSize(1);
    heartbeat.setThreadNamePrefix("ws-heartbeat-");
    heartbeat.initialize();
    registry.enableSimpleBroker("/topic", "/queue").setHeartbeatValue(new long[] {10_000, 10_000})
        .setTaskScheduler(heartbeat);
    registry.setApplicationDestinationPrefixes("/app");
    registry.setUserDestinationPrefix("/user");
  }

  @Override
  public void configureClientInboundChannel(ChannelRegistration registration) {
    registration.interceptors(auth);
  }
}
