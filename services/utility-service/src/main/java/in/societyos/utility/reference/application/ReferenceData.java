package in.societyos.utility.reference.application;

import in.societyos.utility.reference.domain.AssetRef;
import in.societyos.utility.reference.domain.LocationRef;
import in.societyos.utility.reference.infrastructure.AssetRefRepository;
import in.societyos.utility.reference.infrastructure.LocationRefRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read models of assets and locations, and the society calendar (days for sign-off). */
@Service
public class ReferenceData {

  private final AssetRefRepository assets;
  private final LocationRefRepository locations;
  private final Clock clock;
  private final ZoneId zone;

  public ReferenceData(AssetRefRepository assets, LocationRefRepository locations, Clock clock,
      @Value("${sos.default-timezone:Asia/Kolkata}") String zone) {
    this.assets = assets;
    this.locations = locations;
    this.clock = clock;
    this.zone = ZoneId.of(zone);
  }

  @Transactional
  public void upsertAsset(UUID assetId, String code, String name, String categoryGroup, UUID locationId, String status) {
    AssetRef ref = assets.findById(assetId).orElse(null);
    if (ref == null) {
      assets.save(new AssetRef(assetId, code, name, categoryGroup, locationId, status));
    } else {
      ref.update(code, name, categoryGroup, locationId, status);
    }
  }

  @Transactional
  public void upsertLocation(UUID locationId, String kind, String name, UUID towerId, UUID parentId) {
    LocationRef ref = locations.findById(locationId).orElse(null);
    if (ref == null) {
      locations.save(new LocationRef(locationId, kind, name, towerId, parentId));
    } else {
      ref.update(kind, name, towerId, parentId);
    }
  }

  @Transactional(readOnly = true)
  public Optional<AssetRef> asset(UUID assetId) {
    return assetId == null ? Optional.empty() : assets.findById(assetId);
  }

  @Transactional(readOnly = true)
  public Map<UUID, String> assetNames(Collection<UUID> ids) {
    Map<UUID, String> names = new HashMap<>();
    List<UUID> wanted = ids.stream().filter(Objects::nonNull).distinct().toList();
    if (!wanted.isEmpty()) {
      assets.findAllById(wanted).forEach(a -> names.put(a.getId(), a.getName()));
    }
    return names;
  }

  @Transactional(readOnly = true)
  public Map<UUID, String> locationNames(Collection<UUID> ids) {
    Map<UUID, String> names = new HashMap<>();
    List<UUID> wanted = ids.stream().filter(Objects::nonNull).distinct().toList();
    if (!wanted.isEmpty()) {
      locations.findAllById(wanted).forEach(l -> names.put(l.getId(), l.getName()));
    }
    return names;
  }

  public ZoneId zone() {
    return zone;
  }

  public LocalDate today() {
    return LocalDate.now(clock.withZone(zone));
  }

  public Instant now() {
    return clock.instant();
  }

  public Instant startOf(LocalDate date) {
    return date.atStartOfDay(zone).toInstant();
  }
}
