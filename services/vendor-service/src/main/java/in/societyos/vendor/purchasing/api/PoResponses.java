package in.societyos.vendor.purchasing.api;

import in.societyos.vendor.purchasing.application.PurchaseOrderService.PoDetail;
import in.societyos.vendor.purchasing.domain.PoLine;
import in.societyos.vendor.purchasing.domain.PurchaseOrder;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Purchase order DTOs, shared by the staff API and the vendor portal. */
public final class PoResponses {

  private PoResponses() {}

  public record PoSummary(UUID id, String number, UUID vendorId, String title, String status, long totalPaise,
      LocalDate expectedOn, Instant createdAt) {
    public static PoSummary from(PurchaseOrder p) {
      return new PoSummary(p.getId(), p.getNumber(), p.getVendorId(), p.getTitle(), p.getStatus().name(),
          p.getTotalPaise(), p.getExpectedOn(), p.getCreatedAt());
    }
  }

  public record LineResponse(UUID id, int lineNo, String itemCode, UUID spareId, String description, int qty,
      String unit, long unitPricePaise, int gstPercent, long amountPaise, long taxPaise, int receivedQty) {
    public static LineResponse from(PoLine l) {
      return new LineResponse(l.getId(), l.getLineNo(), l.getItemCode(), l.getSpareId(), l.getDescription(), l.getQty(),
          l.getUnit(), l.getUnitPricePaise(), l.getGstPercent(), l.amountPaise(), l.taxPaise(), l.getReceivedQty());
    }
  }

  public record PoResponse(UUID id, String number, UUID vendorId, String vendorName, String title, String status,
      UUID storeId, UUID rfqId, UUID quoteId, long subtotalPaise, long taxPaise, long totalPaise, LocalDate expectedOn,
      Instant submittedAt, UUID workflowInstanceId, Instant decidedAt, String decisionComment,
      List<LineResponse> lines) {
    public static PoResponse from(PoDetail d) {
      PurchaseOrder p = d.po();
      return new PoResponse(p.getId(), p.getNumber(), p.getVendorId(), d.vendorName(), p.getTitle(),
          p.getStatus().name(), p.getStoreId(), p.getRfqId(), p.getQuoteId(), p.getSubtotalPaise(), p.getTaxPaise(),
          p.getTotalPaise(), p.getExpectedOn(), p.getSubmittedAt(), p.getWorkflowInstanceId(), p.getDecidedAt(),
          p.getDecisionComment(), d.lines().stream().map(LineResponse::from).toList());
    }
  }
}
