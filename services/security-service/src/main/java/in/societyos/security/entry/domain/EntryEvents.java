package in.societyos.security.entry.domain;

import in.societyos.security.common.SecurityEvent;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Entry events (contracts/events/CATALOGUE.md, security). Visitor name only; never a phone. */
public final class EntryEvents {

  private EntryEvents() {}

  public record EntryRequested(UUID entryId, UUID flatId, String flatLabel, UUID gateId, String visitorName,
      String purpose, UUID photoMediaId, UUID guardUserId, List<UUID> residentUserIds, Instant expiresAt)
      implements SecurityEvent {
    @Override public String type() { return "security.entry.requested"; }
    @Override public UUID aggregateId() { return entryId; }
  }

  public record EntryApproved(UUID entryId, UUID flatId, UUID decidedBy, Instant decidedAt) implements SecurityEvent {
    @Override public String type() { return "security.entry.approved"; }
    @Override public UUID aggregateId() { return entryId; }
  }

  public record EntryDenied(UUID entryId, UUID flatId, UUID decidedBy, Instant decidedAt) implements SecurityEvent {
    @Override public String type() { return "security.entry.denied"; }
    @Override public UUID aggregateId() { return entryId; }
  }

  public record EntryExpired(UUID entryId, UUID flatId) implements SecurityEvent {
    @Override public String type() { return "security.entry.expired"; }
    @Override public UUID aggregateId() { return entryId; }
  }

  public record EntryCheckedIn(UUID entryId, UUID flatId, String purpose, Instant at) implements SecurityEvent {
    @Override public String type() { return "security.entry.checked_in"; }
    @Override public UUID aggregateId() { return entryId; }
  }

  public record EntryCheckedOut(UUID entryId, UUID flatId, String purpose, Instant at) implements SecurityEvent {
    @Override public String type() { return "security.entry.checked_out"; }
    @Override public UUID aggregateId() { return entryId; }
  }

  public static EntryRequested requested(EntryLog e, List<UUID> residentUserIds) {
    return new EntryRequested(e.getId(), e.getFlatId(), e.getFlatLabel(), e.getGateId(), e.getVisitorName(),
        e.getPurpose(), e.getPhotoMediaId(), e.getGuardId(), List.copyOf(residentUserIds), e.getExpiresAt());
  }

  public static SecurityEvent decided(EntryLog e) {
    return e.status() == EntryLog.Status.APPROVED
        ? new EntryApproved(e.getId(), e.getFlatId(), e.getDecidedBy(), e.getDecidedAt())
        : new EntryDenied(e.getId(), e.getFlatId(), e.getDecidedBy(), e.getDecidedAt());
  }

  public static EntryExpired expired(EntryLog e) {
    return new EntryExpired(e.getId(), e.getFlatId());
  }

  public static EntryCheckedIn checkedIn(EntryLog e) {
    return new EntryCheckedIn(e.getId(), e.getFlatId(), e.getPurpose(), e.getInAt());
  }

  public static EntryCheckedOut checkedOut(EntryLog e) {
    return new EntryCheckedOut(e.getId(), e.getFlatId(), e.getPurpose(), e.getOutAt());
  }
}
