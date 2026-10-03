package in.societyos.vendor.receiving.application;

import in.societyos.vendor.common.Texts;
import in.societyos.vendor.platform.core.error.ProblemException;
import in.societyos.vendor.platform.events.DomainEvents;
import in.societyos.vendor.platform.jpa.DocumentNumberService;
import in.societyos.vendor.purchasing.application.PurchaseOrderService;
import in.societyos.vendor.purchasing.domain.PoLine;
import in.societyos.vendor.purchasing.domain.PurchaseOrder;
import in.societyos.vendor.receiving.domain.Grn;
import in.societyos.vendor.receiving.domain.GrnLine;
import in.societyos.vendor.receiving.domain.GrnRecorded;
import in.societyos.vendor.receiving.infrastructure.GrnLineRepository;
import in.societyos.vendor.receiving.infrastructure.GrnRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Goods received notes: partial receipts against an approved PO, published for inventory. */
@Service
public class GrnService {

  public record LineCommand(UUID poLineId, int receivedQty, Integer acceptedQty) {}

  public record GrnCommand(UUID poId, UUID storeId, LocalDate receivedOn, String challanRef, String note,
      List<LineCommand> lines) {}

  public record GrnDetail(Grn grn, String poNumber, List<GrnLine> lines) {}

  private final GrnRepository grns;
  private final GrnLineRepository grnLines;
  private final PurchaseOrderService orders;
  private final DocumentNumberService numbers;
  private final DomainEvents events;
  private final Clock clock;
  private final String zone;

  public GrnService(GrnRepository grns, GrnLineRepository grnLines, PurchaseOrderService orders,
      DocumentNumberService numbers, DomainEvents events, Clock clock,
      @Value("${sos.default-timezone:Asia/Kolkata}") String zone) {
    this.grns = grns;
    this.grnLines = grnLines;
    this.orders = orders;
    this.numbers = numbers;
    this.events = events;
    this.clock = clock;
    this.zone = zone;
  }

  @Transactional
  public GrnDetail record(GrnCommand c) {
    PurchaseOrder po = orders.forUpdate(c.poId());
    po.requireReceivable();
    if (c.lines() == null || c.lines().isEmpty()) {
      throw ProblemException.badRequest("GRN_EMPTY", "A GRN needs at least one line");
    }
    Map<UUID, PoLine> poLines = orders.lines(po.getId()).stream()
        .collect(Collectors.toMap(PoLine::getId, Function.identity()));
    UUID storeId = c.storeId() != null ? c.storeId() : po.getStoreId();
    LocalDate on = c.receivedOn() != null ? c.receivedOn() : LocalDate.now(clock.withZone(java.time.ZoneId.of(zone)));
    if (on.isAfter(LocalDate.now(clock.withZone(java.time.ZoneId.of(zone))).plusDays(1))) {
      throw ProblemException.badRequest("INVALID_DATE", "receivedOn cannot be in the future");
    }
    Grn grn = new Grn(numbers.next("GRN"), po.getId(), storeId, on, Texts.clean(c.challanRef()), Texts.clean(c.note()));

    Set<UUID> seen = new HashSet<>();
    List<GrnLine> lines = new ArrayList<>();
    List<PoLine> changed = new ArrayList<>();
    List<GrnRecorded.Line> eventLines = new ArrayList<>();
    for (LineCommand l : c.lines()) {
      PoLine pl = poLines.get(l.poLineId());
      if (pl == null) {
        throw ProblemException.badRequest("UNKNOWN_PO_LINE", "Line " + l.poLineId() + " is not on PO " + po.getNumber());
      }
      if (!seen.add(pl.getId())) {
        throw ProblemException.badRequest("DUPLICATE_LINE", "PO line " + pl.getLineNo() + " appears twice");
      }
      GrnLine gl = GrnLine.of(grn.getId(), pl.getId(), l.receivedQty(), l.acceptedQty());
      pl.receive(gl.getAcceptedQty());
      lines.add(gl);
      changed.add(pl);
      if (gl.getAcceptedQty() > 0) {
        eventLines.add(new GrnRecorded.Line(pl.getItemCode(), pl.getSpareId(), gl.getAcceptedQty(),
            pl.getUnitPricePaise()));
      }
    }
    if (lines.stream().allMatch(x -> x.getReceivedQty() == 0)) {
      throw ProblemException.badRequest("GRN_EMPTY", "Nothing was received");
    }
    boolean all = poLines.values().stream().allMatch(PoLine::fullyReceived);
    po.received(all);
    orders.saveReceipt(po, changed);
    grns.save(grn);
    grnLines.saveAll(lines);
    events.publish(new GrnRecorded(grn.getId(), po.getId(), storeId, eventLines));
    return new GrnDetail(grn, po.getNumber(), lines);
  }

  @Transactional(readOnly = true)
  public List<Grn> list(UUID poId) {
    return poId == null ? grns.findTop200ByOrderByCreatedAtDesc() : grns.findByPoIdOrderByCreatedAtAsc(poId);
  }

  @Transactional(readOnly = true)
  public GrnDetail get(UUID id) {
    Grn g = grns.findById(id).orElseThrow(() -> ProblemException.notFound("grn", id));
    return new GrnDetail(g, orders.require(g.getPoId()).getNumber(), grnLines.findByGrnId(id));
  }
}
