package in.societyos.vendor.purchasing.application;

import in.societyos.vendor.common.Texts;
import in.societyos.vendor.platform.core.error.ProblemException;
import in.societyos.vendor.platform.events.DomainEvents;
import in.societyos.vendor.platform.jpa.DocumentNumberService;
import in.societyos.vendor.purchasing.domain.PoEvents;
import in.societyos.vendor.purchasing.domain.PoLine;
import in.societyos.vendor.purchasing.domain.PurchaseOrder;
import in.societyos.vendor.purchasing.infrastructure.PoLineRepository;
import in.societyos.vendor.purchasing.infrastructure.PurchaseOrderRepository;
import in.societyos.vendor.vendor.application.VendorService;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Purchase orders: draft, submit for approval (workflow-service), decision, cancel. */
@Service
public class PurchaseOrderService {

  private static final Logger log = LoggerFactory.getLogger(PurchaseOrderService.class);

  public record LineCommand(String itemCode, UUID spareId, String description, int qty, String unit,
      long unitPricePaise, Integer gstPercent) {}

  public record PoCommand(UUID vendorId, String title, UUID storeId, LocalDate expectedOn, UUID rfqId, UUID quoteId,
      List<LineCommand> lines) {}

  public record PoDetail(PurchaseOrder po, String vendorName, List<PoLine> lines) {}

  private final PurchaseOrderRepository orders;
  private final PoLineRepository lines;
  private final VendorService vendors;
  private final DocumentNumberService numbers;
  private final DomainEvents events;
  private final Clock clock;

  public PurchaseOrderService(PurchaseOrderRepository orders, PoLineRepository lines, VendorService vendors,
      DocumentNumberService numbers, DomainEvents events, Clock clock) {
    this.orders = orders;
    this.lines = lines;
    this.vendors = vendors;
    this.numbers = numbers;
    this.events = events;
    this.clock = clock;
  }

  @Transactional
  public PoDetail create(PoCommand c) {
    var vendor = vendors.require(c.vendorId());
    vendor.requireActive();
    if (c.lines() == null || c.lines().isEmpty()) {
      throw ProblemException.badRequest("PO_EMPTY", "A PO needs at least one line");
    }
    PurchaseOrder po = PurchaseOrder.draft(numbers.next("PO"), vendor.getId(), Texts.clean(c.title()), c.storeId(),
        c.expectedOn(), c.rfqId(), c.quoteId());
    List<PoLine> saved = new ArrayList<>();
    int no = 1;
    for (LineCommand l : c.lines()) {
      saved.add(PoLine.of(po.getId(), no++, Texts.code(l.itemCode()), l.spareId(), Texts.clean(l.description()), l.qty(),
          Texts.upper(l.unit()), l.unitPricePaise(), l.gstPercent() == null ? 18 : l.gstPercent()));
    }
    po.totalsFrom(saved);
    orders.save(po);
    lines.saveAll(saved);
    return new PoDetail(po, vendor.getName(), saved);
  }

  @Transactional
  public PoDetail submit(UUID id) {
    PurchaseOrder po = forUpdate(id);
    vendors.require(po.getVendorId()).requireActive();
    List<PoLine> l = lines.findByPoIdOrderByLineNoAsc(id);
    po.submit(l.size(), clock.instant());
    orders.save(po);
    events.publish(PoEvents.submitted(po));
    return detail(po, l);
  }

  @Transactional
  public PoDetail cancel(UUID id) {
    PurchaseOrder po = forUpdate(id);
    List<PoLine> l = lines.findByPoIdOrderByLineNoAsc(id);
    po.cancel(l.stream().anyMatch(x -> x.getReceivedQty() > 0));
    orders.save(po);
    return detail(po, l);
  }

  @Transactional
  public PoDetail close(UUID id) {
    PurchaseOrder po = forUpdate(id);
    po.close();
    orders.save(po);
    return detail(po, lines.findByPoIdOrderByLineNoAsc(id));
  }

  /** From {@code workflow.approval.requested}: remembers the workflow instance. */
  @Transactional
  public void approvalStarted(UUID poId, UUID instanceId) {
    orders.findForUpdate(poId).ifPresent(po -> {
      po.approvalStarted(instanceId);
      orders.save(po);
    });
  }

  /** From {@code workflow.instance.approved/rejected}: publishes {@code vendor.po.approved/rejected}. */
  @Transactional
  public void decided(UUID poId, UUID instanceId, boolean approved, UUID decidedBy, String comment) {
    PurchaseOrder po = orders.findForUpdate(poId).orElse(null);
    if (po == null) {
      log.warn("Workflow decision for unknown PO {}", poId);
      return;
    }
    if (po.decide(approved, instanceId, decidedBy, Texts.clean(comment), clock.instant())) {
      orders.save(po);
      events.publish(PoEvents.decided(po));
    }
  }

  @Transactional(readOnly = true)
  public List<PurchaseOrder> list(String status, UUID vendorId) {
    if (vendorId != null) {
      return orders.findTop200ByVendorIdOrderByCreatedAtDesc(vendorId);
    }
    if (status != null) {
      return orders.findTop200ByStatusOrderByCreatedAtDesc(parseStatus(status));
    }
    return orders.findTop200ByOrderByCreatedAtDesc();
  }

  /** Vendor portal: the vendor's own POs, drafts excluded. */
  @Transactional(readOnly = true)
  public List<PurchaseOrder> listForVendor(UUID vendorId) {
    return orders.findTop200ByVendorIdAndStatusNotOrderByCreatedAtDesc(vendorId, PurchaseOrder.Status.DRAFT);
  }

  @Transactional(readOnly = true)
  public PoDetail get(UUID id) {
    PurchaseOrder po = require(id);
    return detail(po, lines.findByPoIdOrderByLineNoAsc(id));
  }

  /** Vendor portal: a PO of this vendor that is not a draft, else 404 (never reveals others). */
  @Transactional(readOnly = true)
  public PoDetail getForVendor(UUID id, UUID vendorId) {
    PurchaseOrder po = require(id);
    if (!po.getVendorId().equals(vendorId) || po.getStatus() == PurchaseOrder.Status.DRAFT) {
      throw ProblemException.notFound("purchase_order", id);
    }
    return detail(po, lines.findByPoIdOrderByLineNoAsc(id));
  }

  @Transactional(readOnly = true)
  public PurchaseOrder require(UUID id) {
    return orders.findById(id).orElseThrow(() -> ProblemException.notFound("purchase_order", id));
  }

  /** For receiving and invoicing: the PO row locked for this transaction. */
  @Transactional
  public PurchaseOrder forUpdate(UUID id) {
    return orders.findForUpdate(id).orElseThrow(() -> ProblemException.notFound("purchase_order", id));
  }

  @Transactional(readOnly = true)
  public List<PoLine> lines(UUID poId) {
    return lines.findByPoIdOrderByLineNoAsc(poId);
  }

  @Transactional
  public void saveReceipt(PurchaseOrder po, List<PoLine> changed) {
    lines.saveAll(changed);
    orders.save(po);
  }

  private PoDetail detail(PurchaseOrder po, List<PoLine> l) {
    return new PoDetail(po, vendors.require(po.getVendorId()).getName(), l);
  }

  private static PurchaseOrder.Status parseStatus(String s) {
    try {
      return PurchaseOrder.Status.valueOf(Texts.upper(s));
    } catch (RuntimeException e) {
      throw ProblemException.badRequest("INVALID_STATUS", "Unknown PO status " + s);
    }
  }
}
