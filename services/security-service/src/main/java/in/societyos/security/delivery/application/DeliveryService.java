package in.societyos.security.delivery.application;

import in.societyos.security.delivery.domain.Delivery;
import in.societyos.security.delivery.infrastructure.DeliveryRepository;
import in.societyos.security.directory.application.DirectoryService;
import in.societyos.security.directory.application.GateAccess;
import in.societyos.security.entry.application.EntryService;
import in.societyos.security.entry.application.EntryService.EntryView;
import in.societyos.security.entry.application.EntryService.NewEntry;
import in.societyos.security.notification.application.Notifier;
import in.societyos.security.notification.domain.NotificationRequested;
import in.societyos.security.platform.core.error.ProblemException;
import in.societyos.security.platform.core.tenant.TenantContext;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Deliveries: "leave at gate" parcels wait for collection; others go up after a resident approval. */
@Service
public class DeliveryService {

  public record NewDelivery(UUID flatId, String company, boolean leaveAtGate, UUID gateId, String personName,
      String personPhone, UUID photoMediaId) {}

  public record DeliveryView(Delivery delivery, String flatLabel, EntryView entry) {}

  private final DeliveryRepository deliveries;
  private final EntryService entries;
  private final DirectoryService directory;
  private final GateAccess access;
  private final Notifier notifier;
  private final Clock clock;

  public DeliveryService(DeliveryRepository deliveries, EntryService entries, DirectoryService directory,
      GateAccess access, Notifier notifier, Clock clock) {
    this.deliveries = deliveries;
    this.entries = entries;
    this.directory = directory;
    this.access = access;
    this.notifier = notifier;
    this.clock = clock;
  }

  @Transactional
  public DeliveryView receive(NewDelivery cmd) {
    Instant now = clock.instant();
    String label = directory.requireFlat(cmd.flatId()).getLabel();
    EntryView entry = null;
    if (!cmd.leaveAtGate()) {
      entry = entries.request(new NewEntry(cmd.flatId(), cmd.gateId(),
          cmd.personName() == null || cmd.personName().isBlank() ? cmd.company() : cmd.personName(),
          cmd.personPhone(), cmd.photoMediaId(), "DELIVERY", cmd.company(), null));
    }
    Delivery d = deliveries.save(new Delivery(cmd.flatId(), cmd.company(), cmd.leaveAtGate(),
        entry == null ? null : entry.entry().getId(), cmd.gateId(), access.userId(), now));
    if (cmd.leaveAtGate()) {
      notifier.info(directory.residentUserIds(cmd.flatId()), NotificationRequested.GATE, "gate.delivery.at_gate",
          Map.of("company", d.getCompany(), "flatLabel", label == null ? "" : label, "deliveryId", d.getId().toString()),
          "delivery:" + d.getId());
    }
    return new DeliveryView(d, label, entry);
  }

  @Transactional
  public DeliveryView collect(UUID id) {
    Delivery d = deliveries.findById(id).orElseThrow(() -> ProblemException.notFound("delivery", id));
    if (!access.isGuard() && !access.isResidentOf(d.getFlatId())) {
      throw ProblemException.forbidden("NOT_YOUR_FLAT", "You can only collect parcels for your own flat");
    }
    d.collect(clock.instant());
    return new DeliveryView(deliveries.save(d), label(d), null);
  }

  @Transactional(readOnly = true)
  public List<DeliveryView> list(UUID flatId, String status) {
    UUID society = TenantContext.activeSocietyId();
    List<Delivery> found;
    if (flatId != null) {
      access.requireFlatView(flatId);
      found = deliveries.findBySocietyIdAndFlatIdInOrderByReceivedAtDesc(society, List.of(flatId), Limit.of(100));
    } else if (access.canSeeSocietyLog()) {
      found = status == null || status.isBlank()
          ? deliveries.findBySocietyIdOrderByReceivedAtDesc(society, Limit.of(200))
          : deliveries.findBySocietyIdAndStatusOrderByReceivedAtDesc(society, status.trim().toUpperCase(), Limit.of(200));
    } else {
      List<UUID> mine = access.myFlatIds();
      found = mine.isEmpty() ? List.of()
          : deliveries.findBySocietyIdAndFlatIdInOrderByReceivedAtDesc(society, mine, Limit.of(100));
    }
    Map<UUID, String> labels = directory.labels(found.stream().map(Delivery::getFlatId).toList());
    return found.stream().map(d -> new DeliveryView(d, labels.get(d.getFlatId()), null)).toList();
  }

  @Transactional
  public int purgeBefore(Instant before) {
    return deliveries.purge(TenantContext.activeSocietyId(), before);
  }

  private String label(Delivery d) {
    return directory.labels(List.of(d.getFlatId())).get(d.getFlatId());
  }
}
