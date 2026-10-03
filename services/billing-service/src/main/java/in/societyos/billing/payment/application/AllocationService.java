package in.societyos.billing.payment.application;

import in.societyos.billing.bill.application.BillService;
import in.societyos.billing.bill.domain.Bill;
import in.societyos.billing.payment.domain.Payment;
import in.societyos.billing.payment.domain.PaymentAllocation;
import in.societyos.billing.payment.infrastructure.PaymentAllocationRepository;
import in.societyos.billing.payment.infrastructure.PaymentRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Settles bills from payments. A payment goes first to the bill it names, then to the flat's other
 * open bills oldest due first; whatever is left stays an advance (a credit on the flat's
 * receivable) and is applied automatically when the next bill is published.
 */
@Service
public class AllocationService {

  private final BillService bills;
  private final PaymentRepository payments;
  private final PaymentAllocationRepository allocations;

  public AllocationService(BillService bills, PaymentRepository payments, PaymentAllocationRepository allocations) {
    this.bills = bills;
    this.payments = payments;
    this.allocations = allocations;
  }

  /** Allocates a succeeded payment; returns the paise applied to bills. */
  @Transactional(propagation = Propagation.MANDATORY)
  public long allocate(Payment payment) {
    long remaining = payment.getAmountPaise() - allocations.allocatedOf(payment.getId());
    return allocate(payment, remaining);
  }

  /** Applies the flat's unallocated payments (advances) to its open bills. */
  @Transactional(propagation = Propagation.MANDATORY)
  public long applyAdvances(UUID flatId) {
    long applied = 0;
    for (Payment p : payments.succeededOf(flatId)) {
      long free = p.getAmountPaise() - allocations.allocatedOf(p.getId());
      if (free > 0) {
        long used = allocate(p, free);
        applied += used;
        if (used < free) {
          break; // no open bills left
        }
      }
    }
    return applied;
  }

  private long allocate(Payment payment, long amount) {
    if (amount <= 0) {
      return 0;
    }
    List<Bill> open = new ArrayList<>(bills.openForUpdate(payment.getFlatId()));
    if (payment.getBillId() != null) {
      open.sort((a, b) -> Boolean.compare(!a.getId().equals(payment.getBillId()), !b.getId().equals(payment.getBillId())));
    }
    long remaining = amount;
    for (Bill bill : open) {
      if (remaining == 0) {
        break;
      }
      long applied = bill.applyPayment(remaining);
      if (applied > 0) {
        bills.save(bill);
        allocations.save(new PaymentAllocation(payment.getId(), bill.getId(), applied));
        remaining -= applied;
      }
    }
    return amount - remaining;
  }
}
