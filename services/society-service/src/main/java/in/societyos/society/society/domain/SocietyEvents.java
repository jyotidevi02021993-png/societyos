package in.societyos.society.society.domain;

import in.societyos.society.common.SocietyEvent;
import java.util.UUID;

/** Society lifecycle events (contracts/events/CATALOGUE.md). */
public final class SocietyEvents {

  private SocietyEvents() {}

  /** {@code society.created}: identity-service provisions the default roles on it. */
  public record SocietyCreated(UUID societyId, String name, String city, String state, String timezone)
      implements SocietyEvent {
    @Override
    public String type() {
      return "society.created";
    }

    @Override
    public UUID aggregateId() {
      return societyId;
    }
  }

  /** {@code society.settings.updated}: carries the complete settings after the change. */
  public record SettingsUpdated(UUID societyId, SocietySettings settings) implements SocietyEvent {
    @Override
    public String type() {
      return "society.settings.updated";
    }

    @Override
    public UUID aggregateId() {
      return societyId;
    }
  }

  public record TowerCreated(UUID towerId, String name, String code, int floorsCount) implements SocietyEvent {
    @Override public String type() { return "society.tower.created"; }
    @Override public UUID aggregateId() { return towerId; }
  }

  public record FlatCreated(UUID flatId, UUID towerId, String towerName, String number, String label,
      int floor, Integer areaSqft, String flatType, String status) implements SocietyEvent {
    @Override public String type() { return "society.flat.created"; }
    @Override public UUID aggregateId() { return flatId; }
  }

  public record FlatUpdated(UUID flatId, UUID towerId, String towerName, String number, String label,
      int floor, Integer areaSqft, String flatType, String status) implements SocietyEvent {
    @Override public String type() { return "society.flat.updated"; }
    @Override public UUID aggregateId() { return flatId; }
  }
}
