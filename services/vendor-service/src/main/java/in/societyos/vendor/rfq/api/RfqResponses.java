package in.societyos.vendor.rfq.api;

import in.societyos.vendor.rfq.application.RfqService.QuoteLineCommand;
import in.societyos.vendor.rfq.application.RfqService.RfqDetail;
import in.societyos.vendor.rfq.domain.Quote;
import in.societyos.vendor.rfq.domain.Rfq;
import in.societyos.vendor.rfq.domain.RfqLine;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** RFQ and quote DTOs, shared by the staff API and the vendor portal. */
public final class RfqResponses {

  private RfqResponses() {}

  public record QuoteLineRequest(@NotNull UUID rfqLineId, @PositiveOrZero long unitPricePaise, Integer gstPercent) {
    public QuoteLineCommand command() {
      return new QuoteLineCommand(rfqLineId, unitPricePaise, gstPercent);
    }
  }

  public record RfqSummary(UUID id, String number, String title, String status, LocalDate dueOn) {
    public static RfqSummary from(Rfq r) {
      return new RfqSummary(r.getId(), r.getNumber(), r.getTitle(), r.getStatus().name(), r.getDueOn());
    }
  }

  public record RfqLineResponse(UUID id, int lineNo, String itemCode, UUID spareId, String description, int qty,
      String unit) {
    static RfqLineResponse from(RfqLine l) {
      return new RfqLineResponse(l.getId(), l.getLineNo(), l.getItemCode(), l.getSpareId(), l.getDescription(),
          l.getQty(), l.getUnit());
    }
  }

  public record QuoteResponse(UUID id, UUID rfqId, UUID vendorId, long subtotalPaise, long taxPaise, long totalPaise,
      LocalDate validUntil, Integer deliveryDays, String notes, String status) {
    public static QuoteResponse from(Quote q) {
      return new QuoteResponse(q.getId(), q.getRfqId(), q.getVendorId(), q.getSubtotalPaise(), q.getTaxPaise(),
          q.getTotalPaise(), q.getValidUntil(), q.getDeliveryDays(), q.getNotes(), q.getStatus().name());
    }
  }

  public record RfqResponse(UUID id, String number, String title, String description, String status, UUID storeId,
      List<UUID> invitedVendorIds, LocalDate dueOn, UUID awardedQuoteId, List<RfqLineResponse> lines,
      List<QuoteResponse> quotes) {
    public static RfqResponse from(RfqDetail d) {
      Rfq r = d.rfq();
      return new RfqResponse(r.getId(), r.getNumber(), r.getTitle(), r.getDescription(), r.getStatus().name(),
          r.getStoreId(), r.getInvitedVendorIds(), r.getDueOn(), r.getAwardedQuoteId(),
          d.lines().stream().map(RfqLineResponse::from).toList(), d.quotes().stream().map(QuoteResponse::from).toList());
    }
  }

  public record PortalQuoteRequest(LocalDate validUntil, Integer deliveryDays, String notes,
      @NotEmpty List<@Valid QuoteLineRequest> lines) {}
}
