package in.societyos.realtime.socket.application;

import in.societyos.realtime.socket.infrastructure.PresenceStore;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.messaging.SessionConnectedEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

/** Counts this pod's sockets per user and keeps {@code presence:{userId}} alive while any are open. */
@Service
public class PresenceTracker {

  private final PresenceStore store;
  private final String podId;
  private final Map<UUID, AtomicInteger> sessions = new ConcurrentHashMap<>();

  public PresenceTracker(PresenceStore store, @Value("${HOSTNAME:${spring.application.name}}") String podId) {
    this.store = store;
    this.podId = podId;
  }

  @EventListener
  public void onConnected(SessionConnectedEvent e) {
    if (e.getUser() instanceof StompAuthInterceptor.SocketUser u) {
      sessions.computeIfAbsent(u.userId(), k -> new AtomicInteger()).incrementAndGet();
      store.online(u.userId(), podId);
    }
  }

  @EventListener
  public void onDisconnect(SessionDisconnectEvent e) {
    if (e.getUser() instanceof StompAuthInterceptor.SocketUser u) {
      AtomicInteger n = sessions.get(u.userId());
      if (n != null && n.decrementAndGet() <= 0) {
        sessions.remove(u.userId());
        store.offline(u.userId());
      }
    }
  }

  @Scheduled(fixedDelay = 60_000)
  public void heartbeat() {
    store.refresh(sessions.keySet(), podId);
  }

  public int connectedUsers() {
    return sessions.size();
  }
}
