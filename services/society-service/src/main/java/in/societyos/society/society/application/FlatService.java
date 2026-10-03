package in.societyos.society.society.application;

import in.societyos.society.platform.core.error.ProblemException;
import in.societyos.society.platform.events.DomainEvents;
import in.societyos.society.society.domain.Flat;
import in.societyos.society.society.domain.SocietyEvents;
import in.societyos.society.society.domain.Tower;
import in.societyos.society.society.infrastructure.FlatRepository;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FlatService {

  private final FlatRepository flats;
  private final TowerService towers;
  private final DomainEvents events;

  public FlatService(FlatRepository flats, TowerService towers, DomainEvents events) {
    this.flats = flats;
    this.towers = towers;
    this.events = events;
  }

  @Transactional(readOnly = true)
  public List<Flat> list(UUID towerId) {
    return towerId == null ? flats.findAllByOrderByLabelAsc() : flats.findByTowerIdOrderByNumberAsc(towerId);
  }

  @Transactional(readOnly = true)
  public Flat require(UUID id) {
    return flats.findById(id).orElseThrow(() -> ProblemException.notFound("flat", id));
  }

  @Transactional(readOnly = true)
  public Optional<Flat> findByLabel(String label) {
    return flats.findByLabelIgnoreCase(label.trim());
  }

  /** id → flat for the given ids (missing ids are simply absent). */
  @Transactional(readOnly = true)
  public Map<UUID, Flat> byIds(Collection<UUID> ids) {
    if (ids.isEmpty()) {
      return Map.of();
    }
    return flats.findByIdIn(ids).stream().collect(Collectors.toMap(Flat::getId, Function.identity()));
  }

  @Transactional
  public Flat create(UUID towerId, String number, int floor, Integer areaSqft, String flatType) {
    Tower tower = towers.require(towerId);
    String cleanNumber = number.trim();
    if (flats.existsByTowerIdAndNumberIgnoreCase(tower.getId(), cleanNumber)) {
      throw ProblemException.conflict("FLAT_NUMBER_EXISTS", "This tower already has a flat with this number");
    }
    checkFloor(tower, floor);
    Flat saved = flats.save(new Flat(tower.getId(), cleanNumber, tower.getCode() + "-" + cleanNumber, floor,
        areaSqft, clean(flatType)));
    events.publish(new SocietyEvents.FlatCreated(saved.getId(), tower.getId(), tower.getName(), saved.getNumber(),
        saved.getLabel(), saved.getFloor(), saved.getAreaSqft(), saved.getFlatType(), saved.getStatus()));
    return saved;
  }

  @Transactional
  public Flat update(UUID id, int floor, Integer areaSqft, String flatType, String status) {
    Flat flat = require(id);
    Tower tower = towers.require(flat.getTowerId());
    checkFloor(tower, floor);
    if (!Flat.STATUSES.contains(status)) {
      throw ProblemException.badRequest("INVALID_FLAT_STATUS", "status must be one of " + Flat.STATUSES);
    }
    flat.update(floor, areaSqft, clean(flatType), status);
    Flat saved = flats.save(flat);
    publishUpdated(saved, tower);
    return saved;
  }

  /** Called by memberships: the first owner/tenant moves in, or the last one leaves. */
  @Transactional
  public void occupancyChanged(UUID flatId, boolean occupied) {
    Flat flat = require(flatId);
    if (flat.occupancyChanged(occupied)) {
      publishUpdated(flats.save(flat), towers.require(flat.getTowerId()));
    }
  }

  private void publishUpdated(Flat flat, Tower tower) {
    events.publish(new SocietyEvents.FlatUpdated(flat.getId(), tower.getId(), tower.getName(), flat.getNumber(),
        flat.getLabel(), flat.getFloor(), flat.getAreaSqft(), flat.getFlatType(), flat.getStatus()));
  }

  private static void checkFloor(Tower tower, int floor) {
    if (floor > tower.getFloorsCount()) {
      throw ProblemException.badRequest("INVALID_FLOOR", "Flat floor exceeds the tower's configured floor count");
    }
  }

  private static String clean(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }
}
