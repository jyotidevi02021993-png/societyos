package in.societyos.vendor.purchasing.api;

import in.societyos.vendor.purchasing.api.PoResponses.PoResponse;
import in.societyos.vendor.purchasing.api.PoResponses.PoSummary;
import in.societyos.vendor.purchasing.application.PurchaseOrderService;
import in.societyos.vendor.purchasing.application.PurchaseOrderService.LineCommand;
import in.societyos.vendor.purchasing.application.PurchaseOrderService.PoCommand;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
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

/**
 * Purchase orders. Submitting publishes {@code vendor.po.submitted}; workflow-service runs the
 * approval chain and the decision arrives as {@code workflow.instance.approved/rejected}.
 */
@RestController
class PurchaseOrderController {

  static final String VIEW = "@perm.hasAny('vendor:view', 'po:create', 'po:approve', 'grn:record', 'invoice:approve')";
  static final String CREATE = "@perm.has('po:create')";

  private final PurchaseOrderService orders;

  PurchaseOrderController(PurchaseOrderService orders) {
    this.orders = orders;
  }

  record LineRequest(String itemCode, UUID spareId, String description, @Positive int qty, String unit,
      @PositiveOrZero long unitPricePaise, Integer gstPercent) {}

  record PoRequest(@NotNull UUID vendorId, @NotBlank String title, UUID storeId, LocalDate expectedOn,
      @NotEmpty List<@Valid LineRequest> lines) {}

  @GetMapping("/v1/purchase-orders")
  @PreAuthorize(VIEW)
  List<PoSummary> list(@RequestParam(required = false) String status, @RequestParam(required = false) UUID vendorId) {
    return orders.list(status, vendorId).stream().map(PoSummary::from).toList();
  }

  @PostMapping("/v1/purchase-orders")
  @PreAuthorize(CREATE)
  @ResponseStatus(HttpStatus.CREATED)
  PoResponse create(@Valid @RequestBody PoRequest r) {
    List<LineCommand> lines = r.lines().stream().map(l -> new LineCommand(l.itemCode(), l.spareId(), l.description(),
        l.qty(), l.unit(), l.unitPricePaise(), l.gstPercent())).toList();
    return PoResponse.from(orders.create(new PoCommand(r.vendorId(), r.title(), r.storeId(), r.expectedOn(), null, null,
        lines)));
  }

  @GetMapping("/v1/purchase-orders/{id}")
  @PreAuthorize(VIEW)
  PoResponse get(@PathVariable UUID id) {
    return PoResponse.from(orders.get(id));
  }

  @PostMapping("/v1/purchase-orders/{id}/submit")
  @PreAuthorize(CREATE)
  PoResponse submit(@PathVariable UUID id) {
    return PoResponse.from(orders.submit(id));
  }

  @PostMapping("/v1/purchase-orders/{id}/cancel")
  @PreAuthorize(CREATE)
  PoResponse cancel(@PathVariable UUID id) {
    return PoResponse.from(orders.cancel(id));
  }

  @PostMapping("/v1/purchase-orders/{id}/close")
  @PreAuthorize(CREATE)
  PoResponse close(@PathVariable UUID id) {
    return PoResponse.from(orders.close(id));
  }
}
