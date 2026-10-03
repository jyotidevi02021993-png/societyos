package in.societyos.vendor.rfq.api;

import in.societyos.vendor.purchasing.api.PoResponses.PoResponse;
import in.societyos.vendor.rfq.api.RfqResponses.QuoteLineRequest;
import in.societyos.vendor.rfq.api.RfqResponses.QuoteResponse;
import in.societyos.vendor.rfq.api.RfqResponses.RfqResponse;
import in.societyos.vendor.rfq.api.RfqResponses.RfqSummary;
import in.societyos.vendor.rfq.application.RfqService;
import in.societyos.vendor.rfq.application.RfqService.QuoteCommand;
import in.societyos.vendor.rfq.application.RfqService.RfqCommand;
import in.societyos.vendor.rfq.application.RfqService.RfqLineCommand;
import in.societyos.vendor.rfq.domain.QuoteComparison;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Requests for quotation, quotes, comparison and award (which raises a draft PO). */
@RestController
class RfqController {

  static final String VIEW = "@perm.hasAny('vendor:view', 'rfq:manage', 'po:create', 'po:approve')";
  static final String MANAGE = "@perm.has('rfq:manage')";

  private final RfqService rfqs;

  RfqController(RfqService rfqs) {
    this.rfqs = rfqs;
  }

  record LineRequest(String itemCode, UUID spareId, String description, @Positive int qty, String unit) {}

  record RfqRequest(@NotBlank String title, String description, UUID storeId, List<UUID> invitedVendorIds,
      LocalDate dueOn, @NotEmpty List<@Valid LineRequest> lines) {}

  record QuoteRequest(@NotNull UUID vendorId, LocalDate validUntil, Integer deliveryDays, String notes,
      @NotEmpty List<@Valid QuoteLineRequest> lines) {}

  record AwardRequest(@NotNull UUID quoteId) {}

  @GetMapping("/v1/rfqs")
  @PreAuthorize(VIEW)
  List<RfqSummary> list(@RequestParam(required = false) String status) {
    return rfqs.list(status).stream().map(RfqSummary::from).toList();
  }

  @PostMapping("/v1/rfqs")
  @PreAuthorize(MANAGE)
  @ResponseStatus(HttpStatus.CREATED)
  RfqResponse create(@Valid @RequestBody RfqRequest r) {
    List<RfqLineCommand> lines = r.lines().stream()
        .map(l -> new RfqLineCommand(l.itemCode(), l.spareId(), l.description(), l.qty(), l.unit())).toList();
    return RfqResponse.from(rfqs.create(new RfqCommand(r.title(), r.description(), r.storeId(), r.invitedVendorIds(),
        r.dueOn(), lines)));
  }

  @GetMapping("/v1/rfqs/{id}")
  @PreAuthorize(VIEW)
  RfqResponse get(@PathVariable UUID id) {
    return RfqResponse.from(rfqs.get(id));
  }

  @PostMapping("/v1/rfqs/{id}/quotes")
  @PreAuthorize(MANAGE)
  @ResponseStatus(HttpStatus.CREATED)
  QuoteResponse quote(@PathVariable UUID id, @Valid @RequestBody QuoteRequest r) {
    return QuoteResponse.from(rfqs.submitQuote(id, new QuoteCommand(r.vendorId(), r.validUntil(), r.deliveryDays(),
        r.notes(), r.lines().stream().map(QuoteLineRequest::command).toList()), false));
  }

  @GetMapping("/v1/rfqs/{id}/comparison")
  @PreAuthorize(VIEW)
  QuoteComparison.Result comparison(@PathVariable UUID id) {
    return rfqs.compare(id);
  }

  @PostMapping("/v1/rfqs/{id}/award")
  @PreAuthorize("@perm.has('rfq:manage') and @perm.has('po:create')")
  PoResponse award(@PathVariable UUID id, @Valid @RequestBody AwardRequest r) {
    return PoResponse.from(rfqs.award(id, r.quoteId()));
  }

  @PostMapping("/v1/rfqs/{id}/cancel")
  @PreAuthorize(MANAGE)
  RfqSummary cancel(@PathVariable UUID id) {
    return RfqSummary.from(rfqs.cancel(id));
  }
}
