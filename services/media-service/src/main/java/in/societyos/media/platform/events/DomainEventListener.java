package in.societyos.media.platform.events;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method {@code void on(CloudEvent<MyData> event)} as a consumer of domain events.
 *
 * <p>The platform subscribes group {@link #group()} to {@link #topic()}, ignores records whose
 * {@code ce_type} header is not in {@link #type()}, binds the tenant from the event's society,
 * and runs the handler in a transaction with an inbox row so a redelivered event has no effect.
 * Failures retry three times (1 s, 2 s, 4 s) and then go to {@code sos.dlq.<group>}.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface DomainEventListener {

  /** e.g. {@code sos.society.events.v1} */
  String topic();

  /** Consumer group, {@code <service>.<purpose>}, e.g. {@code gate.flat-directory}. */
  String group();

  /** Event types handled; empty means every type on the topic. */
  String[] type() default {};
}
