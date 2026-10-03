package in.societyos.society.location.application;

import in.societyos.society.location.domain.Location;
import in.societyos.society.location.domain.LocationCreated;
import in.societyos.society.location.infrastructure.LocationRepository;
import in.societyos.society.platform.core.error.ProblemException;
import in.societyos.society.platform.events.DomainEvents;
import in.societyos.society.society.application.TowerService;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LocationService {

  private final LocationRepository locations;
  private final TowerService towers;
  private final DomainEvents events;

  public LocationService(LocationRepository locations, TowerService towers, DomainEvents events) {
    this.locations = locations;
    this.towers = towers;
    this.events = events;
  }

  @Transactional(readOnly = true)
  public List<Location> list() {
    return locations.findAllByOrderByNameAsc();
  }

  @Transactional
  public Location create(String kind, String name, UUID towerId, UUID parentId) {
    String cleanName = name.trim();
    check(kind, towerId, parentId);
    if (nameTaken(parentId, cleanName)) {
      throw ProblemException.conflict("LOCATION_NAME_EXISTS", "A location with this name already exists here");
    }
    Location saved = locations.save(new Location(kind, cleanName, towerId, parentId));
    events.publish(new LocationCreated(saved.getId(), saved.getKind(), saved.getName(), saved.getTowerId(),
        saved.getParentId()));
    return saved;
  }

  @Transactional
  public Location update(UUID id, String kind, String name, UUID towerId, UUID parentId) {
    Location location = locations.findById(id).orElseThrow(() -> ProblemException.notFound("location", id));
    String cleanName = name.trim();
    check(kind, towerId, parentId);
    if (parentId != null && createsCycle(id, parentId)) {
      throw ProblemException.unprocessable("LOCATION_CYCLE", "A location cannot sit inside itself");
    }
    boolean samePlace = cleanName.equalsIgnoreCase(location.getName())
        && Objects.equals(parentId, location.getParentId());
    if (!samePlace && nameTaken(parentId, cleanName)) {
      throw ProblemException.conflict("LOCATION_NAME_EXISTS", "A location with this name already exists here");
    }
    location.update(kind, cleanName, towerId, parentId);
    return locations.save(location);
  }

  private void check(String kind, UUID towerId, UUID parentId) {
    if (!Location.KINDS.contains(kind)) {
      throw ProblemException.badRequest("INVALID_LOCATION_KIND", "kind must be one of " + Location.KINDS);
    }
    if (towerId != null) {
      towers.require(towerId);
    }
    if (parentId != null && !locations.existsById(parentId)) {
      throw ProblemException.notFound("location", parentId);
    }
  }

  private boolean nameTaken(UUID parentId, String name) {
    return parentId == null
        ? locations.existsByParentIdIsNullAndNameIgnoreCase(name)
        : locations.existsByParentIdAndNameIgnoreCase(parentId, name);
  }

  /** Walks up from the new parent; reaching {@code id} means the move would create a loop. */
  private boolean createsCycle(UUID id, UUID newParentId) {
    Set<UUID> seen = new HashSet<>();
    UUID current = newParentId;
    while (current != null && seen.add(current)) {
      if (current.equals(id)) {
        return true;
      }
      current = locations.findById(current).map(Location::getParentId).orElse(null);
    }
    return false;
  }
}
