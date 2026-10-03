package in.societyos.asset.reference.application;

import in.societyos.asset.platform.core.tenant.TenantContext;
import in.societyos.asset.reference.domain.LocationRef;
import in.societyos.asset.reference.domain.VendorRef;
import in.societyos.asset.reference.infrastructure.LocationRefRepository;
import in.societyos.asset.reference.infrastructure.SocietyDirectory;
import in.societyos.asset.reference.infrastructure.VendorRefRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read models fed by other services' events, plus the society calendar. */
@Service
public class ReferenceData {

  private final LocationRefRepository locations;
  private final VendorRefRepository vendors;
  private final SocietyDirectory societies;
  private final Clock clock;

  public ReferenceData(LocationRefRepository locations, VendorRefRepository vendors, SocietyDirectory societies,
      Clock clock) {
    this.locations = locations;
    this.vendors = vendors;
    this.societies = societies;
    this.clock = clock;
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

  @Transactional
  public void upsertVendor(UUID vendorId, String code, String name, String status) {
    VendorRef ref = vendors.findById(vendorId).orElse(null);
    if (ref == null) {
      vendors.save(new VendorRef(vendorId, code, name, status));
    } else {
      ref.update(code, name, status);
    }
  }

  public void registerSociety(UUID societyId, String name, String timezone) {
    societies.register(societyId, name, timezone);
  }

  /** Called on every asset write so the scheduler knows this society. */
  public void ensureSocietyKnown() {
    societies.ensure(TenantContext.activeSocietyId());
  }

  /** Today in the active society's time zone. */
  public LocalDate today() {
    return LocalDate.now(clock.withZone(zone()));
  }

  public ZoneId zone() {
    return societies.zoneOf(TenantContext.activeSocietyId());
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

  @Transactional(readOnly = true)
  public Map<UUID, String> vendorNames(Collection<UUID> ids) {
    Map<UUID, String> names = new HashMap<>();
    List<UUID> wanted = ids.stream().filter(Objects::nonNull).distinct().toList();
    if (!wanted.isEmpty()) {
      vendors.findAllById(wanted).forEach(v -> names.put(v.getId(), v.getName()));
    }
    return names;
  }

  @Transactional(readOnly = true)
  public List<VendorRef> vendors() {
    return vendors.findAll();
  }
}
