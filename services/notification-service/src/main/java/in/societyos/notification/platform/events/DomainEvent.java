package in.societyos.notification.platform.events;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.util.UUID;

/**
 * A fact another bounded context may care about. Implemented by immutable records whose
 * components are the event data. Publish only through {@link DomainEvents#publish}.
 */
public interface DomainEvent {

  /** {@code <context>.<entity>.<past-tense-verb>}, e.g. {@code gate.entry.requested}. */
  @JsonIgnore
  String type();

  /** Bounded context; routes to topic {@code sos.<context>.events.v1}. */
  @JsonIgnore
  String context();

  /** Kafka key: all events of one aggregate stay in order. */
  @JsonIgnore
  UUID aggregateId();

  /** CloudEvents subject, e.g. {@code entry/0192...}. */
  @JsonIgnore
  default String subject() {
    String[] parts = type().split("\\.");
    String entity = parts.length > 1 ? parts[1] : parts[0];
    return entity + "/" + aggregateId();
  }

  /** Schema major version; bump only for a breaking change (new {@code …v2} topic). */
  @JsonIgnore
  default int schemaVersion() {
    return 1;
  }
}
