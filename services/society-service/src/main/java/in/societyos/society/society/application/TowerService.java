package in.societyos.society.society.application;

import in.societyos.society.platform.core.error.ProblemException;
import in.societyos.society.platform.events.DomainEvents;
import in.societyos.society.society.domain.SocietyEvents;
import in.societyos.society.society.domain.Tower;
import in.societyos.society.society.infrastructure.FlatRepository;
import in.societyos.society.society.infrastructure.TowerRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TowerService {

  private final TowerRepository towers;
  private final FlatRepository flats;
  private final DomainEvents events;

  public TowerService(TowerRepository towers, FlatRepository flats, DomainEvents events) {
    this.towers = towers;
    this.flats = flats;
    this.events = events;
  }

  @Transactional(readOnly = true)
  public List<Tower> list() {
    return towers.findAllByOrderByCodeAsc();
  }

  @Transactional(readOnly = true)
  public Tower require(UUID id) {
    return towers.findById(id).orElseThrow(() -> ProblemException.notFound("tower", id));
  }

  @Transactional(readOnly = true)
  public Optional<Tower> findByCode(String code) {
    return towers.findByCodeIgnoreCase(code.trim());
  }

  @Transactional
  public Tower create(String name, String code, int floorsCount) {
    String cleanCode = code.trim();
    if (towers.findByCodeIgnoreCase(cleanCode).isPresent()) {
      throw ProblemException.conflict("TOWER_CODE_EXISTS", "A tower with this code already exists");
    }
    Tower saved = towers.save(new Tower(name.trim(), cleanCode, floorsCount));
    events.publish(new SocietyEvents.TowerCreated(saved.getId(), saved.getName(), saved.getCode(), saved.getFloorsCount()));
    return saved;
  }

  /**
   * Renames or resizes a tower. The code is part of every flat label ("A-1203"), which other
   * services store, so it cannot change once the tower has flats.
   */
  @Transactional
  public Tower update(UUID id, String name, String code, int floorsCount) {
    Tower tower = require(id);
    String cleanCode = code.trim();
    towers.findByCodeIgnoreCase(cleanCode).filter(other -> !other.getId().equals(id)).ifPresent(other -> {
      throw ProblemException.conflict("TOWER_CODE_EXISTS", "A tower with this code already exists");
    });
    if (!tower.getCode().equals(cleanCode) && flats.existsByTowerId(id)) {
      throw ProblemException.unprocessable("TOWER_CODE_LOCKED", "The tower code cannot change once it has flats");
    }
    if (floorsCount < flats.maxFloor(id).orElse(0)) {
      throw ProblemException.unprocessable("TOWER_FLOORS_IN_USE", "Some flats are above the new floor count");
    }
    tower.update(name.trim(), cleanCode, floorsCount);
    return towers.save(tower);
  }
}
