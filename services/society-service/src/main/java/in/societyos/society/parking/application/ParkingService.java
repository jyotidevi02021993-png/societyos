package in.societyos.society.parking.application;

import in.societyos.society.parking.domain.ParkingSlot;
import in.societyos.society.parking.infrastructure.ParkingSlotRepository;
import in.societyos.society.platform.core.error.ProblemException;
import in.societyos.society.society.application.FlatService;
import in.societyos.society.society.domain.Flat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ParkingService {

  /** A slot with the label of the flat it is allotted to (null when free). */
  public record SlotView(ParkingSlot slot, String flatLabel) {}

  private final ParkingSlotRepository slots;
  private final FlatService flats;

  public ParkingService(ParkingSlotRepository slots, FlatService flats) {
    this.slots = slots;
    this.flats = flats;
  }

  @Transactional(readOnly = true)
  public List<SlotView> list(UUID flatId) {
    List<ParkingSlot> found = flatId == null ? slots.findAllByOrderByCodeAsc() : slots.findByFlatIdOrderByCodeAsc(flatId);
    Map<UUID, Flat> byId = flats.byIds(found.stream().map(ParkingSlot::getFlatId).filter(Objects::nonNull).toList());
    return found.stream().map(s -> view(s, byId.get(s.getFlatId()))).toList();
  }

  @Transactional
  public SlotView create(String code, String kind) {
    String cleanCode = code.trim().toUpperCase();
    if (!ParkingSlot.KINDS.contains(kind)) {
      throw ProblemException.badRequest("INVALID_PARKING_KIND", "kind must be one of " + ParkingSlot.KINDS);
    }
    if (slots.existsByCodeIgnoreCase(cleanCode)) {
      throw ProblemException.conflict("PARKING_CODE_EXISTS", "A parking slot with this code already exists");
    }
    return view(slots.save(new ParkingSlot(cleanCode, kind)), null);
  }

  @Transactional
  public SlotView assign(UUID slotId, UUID flatId) {
    ParkingSlot slot = slots.findById(slotId).orElseThrow(() -> ProblemException.notFound("parking_slot", slotId));
    Flat flat = flatId == null ? null : flats.require(flatId);
    try {
      slot.assign(flatId);
    } catch (IllegalStateException e) {
      throw ProblemException.unprocessable("VISITOR_SLOT", e.getMessage());
    }
    return view(slots.save(slot), flat);
  }

  private static SlotView view(ParkingSlot slot, Flat flat) {
    return new SlotView(slot, flat == null ? null : flat.getLabel());
  }
}
