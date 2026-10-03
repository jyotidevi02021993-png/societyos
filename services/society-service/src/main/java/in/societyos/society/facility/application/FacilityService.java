package in.societyos.society.facility.application;

import in.societyos.society.facility.domain.BookingRules;
import in.societyos.society.facility.domain.Facility;
import in.societyos.society.facility.domain.FacilityEvents;
import in.societyos.society.facility.infrastructure.FacilityRepository;
import in.societyos.society.platform.core.error.ProblemException;
import in.societyos.society.platform.events.DomainEvents;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@Service
public class FacilityService {

  /** A facility with its parsed booking rules. */
  public record FacilityView(Facility facility, BookingRules rules) {}

  private final FacilityRepository facilities;
  private final JsonMapper jsonMapper;
  private final DomainEvents events;

  public FacilityService(FacilityRepository facilities, JsonMapper jsonMapper, DomainEvents events) {
    this.facilities = facilities;
    this.jsonMapper = jsonMapper;
    this.events = events;
  }

  @Transactional(readOnly = true)
  public List<FacilityView> list() {
    return facilities.findAllByOrderByNameAsc().stream().map(this::view).toList();
  }

  @Transactional
  public FacilityView create(Facility.Details details, BookingRules rules) {
    Facility.Details d = validated(details);
    BookingRules r = (rules == null ? BookingRules.defaults() : rules).validated();
    if (facilities.findByNameIgnoreCase(d.name()).isPresent()) {
      throw ProblemException.conflict("FACILITY_NAME_EXISTS", "A facility with this name already exists");
    }
    Facility saved = facilities.save(new Facility(d, jsonMapper.writeValueAsString(r)));
    events.publish(new FacilityEvents.FacilityCreated(saved.getId(), saved.getKind(), saved.getName(),
        saved.getCapacity(), saved.isChargeable(), saved.getChargePaise(), r, saved.getStatus()));
    return new FacilityView(saved, r);
  }

  @Transactional
  public FacilityView update(UUID id, Facility.Details details, BookingRules rules) {
    Facility facility = facilities.findById(id).orElseThrow(() -> ProblemException.notFound("facility", id));
    Facility.Details d = validated(details);
    BookingRules r = (rules == null ? parse(facility.getBookingRulesJson()) : rules).validated();
    facilities.findByNameIgnoreCase(d.name()).filter(other -> !other.getId().equals(id)).ifPresent(other -> {
      throw ProblemException.conflict("FACILITY_NAME_EXISTS", "A facility with this name already exists");
    });
    facility.update(d, jsonMapper.writeValueAsString(r));
    Facility saved = facilities.save(facility);
    events.publish(new FacilityEvents.FacilityUpdated(saved.getId(), saved.getKind(), saved.getName(),
        saved.getCapacity(), saved.isChargeable(), saved.getChargePaise(), r, saved.getStatus()));
    return new FacilityView(saved, r);
  }

  private FacilityView view(Facility f) {
    return new FacilityView(f, parse(f.getBookingRulesJson()));
  }

  private BookingRules parse(String json) {
    BookingRules parsed = json == null || json.isBlank() ? null : jsonMapper.readValue(json, BookingRules.class);
    return (parsed == null ? BookingRules.defaults() : parsed).validated();
  }

  private static Facility.Details validated(Facility.Details d) {
    if (!Facility.KINDS.contains(d.kind())) {
      throw ProblemException.badRequest("INVALID_FACILITY_KIND", "kind must be one of " + Facility.KINDS);
    }
    if (d.status() != null && !Facility.STATUSES.contains(d.status())) {
      throw ProblemException.badRequest("INVALID_FACILITY_STATUS", "status must be one of " + Facility.STATUSES);
    }
    if (d.chargeable() && d.chargePaise() <= 0) {
      throw ProblemException.badRequest("INVALID_CHARGE", "A chargeable facility needs a charge above zero");
    }
    return new Facility.Details(d.kind(), d.name().trim(), d.capacity(), d.chargeable(), d.chargePaise(), d.status());
  }
}
