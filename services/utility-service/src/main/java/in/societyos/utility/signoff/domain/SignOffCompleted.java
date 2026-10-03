package in.societyos.utility.signoff.domain;

import in.societyos.utility.common.UtilityDomainEvent;
import java.time.LocalDate;
import java.util.UUID;

/** {@code utility.signoff.completed}. */
public record SignOffCompleted(UUID signoffId, LocalDate date, UUID managerUserId) implements UtilityDomainEvent {
  @Override public String type() { return "utility.signoff.completed"; }
  @Override public UUID aggregateId() { return signoffId; }
}
