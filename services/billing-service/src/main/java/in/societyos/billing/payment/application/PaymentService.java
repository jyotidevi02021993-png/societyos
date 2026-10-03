package in.societyos.billing.payment.application;

import in.societyos.billing.bill.application.BillService;
import in.societyos.billing.bill.domain.Bill;
import in.societyos.billing.common.BillingClock;
import in.societyos.billing.common.Paise;
import in.societyos.billing.ledger.application.LedgerService;
import in.societyos.billing.ledger.domain.Journal;
import in.societyos.billing.ledger.domain.Journal.Account;
import in.societyos.billing.notification.application.BillingNotifier;
import in.societyos.billing.payment.domain.Payment;
import in.societyos.billing.payment.domain.PaymentAllocation;
import in.societyos.billing.payment.domain.PaymentEvents;
import in.societyos.billing.payment.domain.Receipt;
import in.societyos.billing.payment.infrastructure.PaymentAllocationRepository;
import in.societyos.billing.payment.infrastructure.PaymentRepository;
import in.societyos.billing.payment.infrastructure.ReceiptRepository;
import in.societyos.billing.platform.core.error.ProblemException;
import in.societyos.billing.platform.core.tenant.TenantContext;
import in.societyos.billing.platform.events.DomainEvents;
import in.societyos.billing.platform.jpa.DocumentNumberService;
import in.societyos.billing.roster.application.BillingAccess;
import in.societyos.billing.roster.application.RosterService;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Payments: online (gateway order → webhook) and offline (cash, cheque, UPI reference, bank
 * transfer). A succeeded payment, in one transaction: settles bills, posts Dr Bank/Cash · Cr
 * Member receivable, issues a receipt (RCPT-…), publishes {@code billing.payment.succeeded} and
 * {@code billing.receipt.issued}, and asks for a "payment received" notification.
 */
@Service
public class PaymentService {

  public record OnlineStart(Payment payment, PaymentGateway.Order order) {}

  public record PaymentView(Payment payment, List<PaymentAllocation> allocations, Receipt receipt) {}

  static final Duration RECONCILE_AFTER = Duration.ofMinutes(10);
  static final Duration EXPIRE_AFTER = Duration.ofHours(24);

  private final PaymentRepository payments;
  private final PaymentAllocationRepository allocations;
  private final ReceiptRepository receipts;
  private final AllocationService allocator;
  private final BillService bills;
  private final LedgerService ledger;
  private final DocumentNumberService numbers;
  private final DomainEvents events;
  private final BillingNotifier notifier;
  private final BillingAccess access;
  private final RosterService roster;
  private final BillingClock clock;
  private final Map<String, PaymentGateway> gateways;
  private final String defaultGateway;

  public PaymentService(PaymentRepository payments, PaymentAllocationRepository allocations,
      ReceiptRepository receipts, AllocationService allocator, BillService bills, LedgerService ledger,
      DocumentNumberService numbers, DomainEvents events, BillingNotifier notifier, BillingAccess access,
      RosterService roster, BillingClock clock, List<PaymentGateway> gateways,
      @Value("${sos.billing.gateway.default:stub}") String defaultGateway) {
    this.payments = payments;
    this.allocations = allocations;
    this.receipts = receipts;
    this.allocator = allocator;
    this.bills = bills;
    this.ledger = ledger;
    this.numbers = numbers;
    this.events = events;
    this.notifier = notifier;
    this.access = access;
    this.roster = roster;
    this.clock = clock;
    this.gateways = gateways.stream().collect(Collectors.toMap(PaymentGateway::provider, Function.identity()));
    this.defaultGateway = defaultGateway;
  }

  public PaymentGateway gateway(String provider) {
    PaymentGateway g = gateways.get(provider);
    if (g == null) {
      throw ProblemException.notFound("gateway", provider);
    }
    return g;
  }

  /** A resident starts paying a bill online: creates the gateway order for the hosted checkout. */
  @Transactional
  public OnlineStart startOnline(UUID billId, Long amountPaise) {
    Bill bill = bills.require(billId);
    access.requirePay(bill.getFlatId());
    if (!bill.isOpen()) {
      throw ProblemException.unprocessable("BILL_NOT_OPEN", "This bill has nothing left to pay");
    }
    long amount = amountPaise == null ? bill.getBalancePaise() : amountPaise;
    if (amount <= 0 || amount > bill.getBalancePaise()) {
      throw ProblemException.badRequest("INVALID_AMOUNT", "Pay between 1 paisa and the bill balance");
    }
    PaymentGateway gateway = gateway(defaultGateway);
    Payment p = Payment.online(bill.getFlatId(), bill.getId(), amount, gateway.provider());
    PaymentGateway.Order order = gateway.createOrder(p.getId(), amount,
        Map.of("societyId", TenantContext.activeSocietyId().toString(), "billNumber", bill.getNumber()));
    p.orderCreated(order.orderId());
    payments.save(p);
    return new OnlineStart(p, order);
  }

  /** Accounts records money received at the office. */
  @Transactional
  public PaymentView recordOffline(UUID flatId, UUID billId, long amountPaise, String method, String reference,
      LocalDate paidOn) {
    String m = method == null ? "" : method.trim().toUpperCase();
    if (!Payment.OFFLINE_METHODS.contains(m)) {
      throw ProblemException.badRequest("INVALID_METHOD", "method must be one of " + Payment.OFFLINE_METHODS);
    }
    if (!"CASH".equals(m) && (reference == null || reference.isBlank())) {
      throw ProblemException.badRequest("REFERENCE_REQUIRED", "Cheque, UPI and bank transfers need a reference");
    }
    if (amountPaise <= 0) {
      throw ProblemException.badRequest("INVALID_AMOUNT", "amountPaise must be positive");
    }
    UUID flat = flatId;
    if (billId != null) {
      Bill bill = bills.require(billId);
      if (flat != null && !flat.equals(bill.getFlatId())) {
        throw ProblemException.badRequest("BILL_FLAT_MISMATCH", "The bill belongs to another flat");
      }
      flat = bill.getFlatId();
    }
    if (flat == null) {
      throw ProblemException.badRequest("FLAT_REQUIRED", "Give a flatId or a billId");
    }
    roster.requireFlat(flat);
    LocalDate today = clock.today();
    if (paidOn != null && paidOn.isAfter(today)) {
      throw ProblemException.badRequest("INVALID_DATE", "paidOn cannot be in the future");
    }
    Instant paidAt = paidOn == null || paidOn.equals(today) ? clock.now()
        : paidOn.atStartOfDay(java.time.ZoneOffset.UTC).toInstant();
    Payment p = payments.save(Payment.offline(flat, billId, amountPaise, m,
        reference == null ? null : reference.trim(), paidAt));
    Receipt r = settle(p);
    return new PaymentView(p, allocations.findByPaymentId(p.getId()), r);
  }

  /** Gateway capture (webhook or reconciliation). Idempotent: a settled payment is left alone. */
  @Transactional(propagation = Propagation.MANDATORY)
  public boolean captured(String orderId, String gatewayPaymentId, long amountPaise) {
    Payment p = payments.lockByOrderId(orderId).orElse(null);
    if (p == null || !p.isPending()) {
      return false;
    }
    p.succeed(gatewayPaymentId, amountPaise, clock.now());
    payments.save(p);
    settle(p);
    return true;
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public boolean failed(String orderId, String reason) {
    Payment p = payments.lockByOrderId(orderId).orElse(null);
    if (p == null || !p.isPending()) {
      return false;
    }
    p.fail(reason);
    payments.save(p);
    events.publish(new PaymentEvents.PaymentFailed(p.getId(), p.getBillId(), p.getFlatId(), p.getAmountPaise(),
        p.getFailureReason()));
    return true;
  }

  /** Lost webhooks: asks the gateway about orders pending over 10 minutes; expires them after 24 h. */
  @Transactional
  public int reconcile() {
    Instant now = clock.now();
    int settled = 0;
    for (Payment p : payments.pendingOnlineBefore(now.minus(RECONCILE_AFTER))) {
      PaymentGateway g = gateways.get(p.getGateway());
      var status = g == null ? java.util.Optional.<PaymentGateway.OrderStatus>empty() : g.fetchStatus(p.getGatewayOrderId());
      if (status.isPresent() && status.get().outcome() == PaymentGateway.Outcome.CAPTURED) {
        settled += captured(p.getGatewayOrderId(), status.get().gatewayPaymentId(), status.get().amountPaise()) ? 1 : 0;
      } else if (status.isPresent() && status.get().outcome() == PaymentGateway.Outcome.FAILED) {
        settled += failed(p.getGatewayOrderId(), status.get().reason()) ? 1 : 0;
      } else if (p.getCreatedAt().isBefore(now.minus(EXPIRE_AFTER))) {
        settled += failed(p.getGatewayOrderId(), "EXPIRED") ? 1 : 0;
      }
    }
    return settled;
  }

  @Transactional(readOnly = true)
  public List<Payment> list(UUID flatId, int limit) {
    if (flatId != null) {
      access.requirePaymentRead(flatId);
      return payments.findByFlatIdInOrderByCreatedAtDesc(List.of(flatId), Limit.of(limit));
    }
    if (access.canViewAllPayments()) {
      return payments.findAllByOrderByCreatedAtDesc(Limit.of(limit));
    }
    List<UUID> own = access.ownFlats();
    return own.isEmpty() ? List.of() : payments.findByFlatIdInOrderByCreatedAtDesc(own, Limit.of(limit));
  }

  @Transactional(readOnly = true)
  public PaymentView get(UUID id) {
    Payment p = payments.findById(id).orElseThrow(() -> ProblemException.notFound("payment", id));
    access.requirePaymentRead(p.getFlatId());
    return new PaymentView(p, allocations.findByPaymentId(id), receipts.findByPaymentId(id).orElse(null));
  }

  @Transactional(readOnly = true)
  public List<Receipt> receipts(UUID flatId, int limit) {
    if (flatId != null) {
      access.requirePaymentRead(flatId);
      return receipts.findByFlatIdInOrderByIssuedAtDesc(List.of(flatId), Limit.of(limit));
    }
    if (access.canViewAllPayments()) {
      return receipts.findAllByOrderByIssuedAtDesc(Limit.of(limit));
    }
    List<UUID> own = access.ownFlats();
    return own.isEmpty() ? List.of() : receipts.findByFlatIdInOrderByIssuedAtDesc(own, Limit.of(limit));
  }

  @Transactional(readOnly = true)
  public Receipt receipt(UUID id) {
    Receipt r = receipts.findById(id).orElseThrow(() -> ProblemException.notFound("receipt", id));
    access.requirePaymentRead(r.getFlatId());
    return r;
  }

  private Receipt settle(Payment p) {
    allocator.allocate(p);
    String flatLabel = roster.flatsById(List.of(p.getFlatId())).values().stream().findFirst()
        .map(f -> f.getLabel()).orElse("?");
    Receipt receipt = receipts.save(new Receipt(p, flatLabel, numbers.next("RCPT"), clock.now()));
    Account cashOrBank = "CASH".equals(p.getMethod()) ? Account.CASH : Account.BANK;
    ledger.post(new Journal("PAYMENT", p.getId(), receipt.getNumber() + " " + p.getMethod()
        + (p.getReference() == null ? "" : " " + p.getReference()), clock.dateOf(p.getPaidAt()))
        .debit(cashOrBank, p.getAmountPaise())
        .credit(Account.MEMBER_RECEIVABLE, p.getFlatId(), p.getAmountPaise()));
    events.publish(PaymentEvents.PaymentSucceeded.of(p));
    events.publish(new PaymentEvents.ReceiptIssued(receipt.getId(), p.getId(), p.getFlatId(), receipt.getNumber()));
    long outstanding = ledger.outstandingOf(p.getFlatId());
    notifier.toFlat(p.getFlatId(), BillingNotifier.PAYMENT_RECEIVED, Map.of(
        "flatLabel", flatLabel,
        "amount", Paise.rupees(p.getAmountPaise()),
        "receiptNumber", receipt.getNumber(),
        "outstanding", Paise.rupees(Math.max(0, outstanding))), "payment:" + p.getId(), false);
    return receipt;
  }
}
