package in.societyos.vendor.receiving.api;

import in.societyos.vendor.receiving.application.GrnService;
import in.societyos.vendor.receiving.application.GrnService.GrnCommand;
import in.societyos.vendor.receiving.application.GrnService.GrnDetail;
import in.societyos.vendor.receiving.application.GrnService.LineCommand;
import in.societyos.vendor.receiving.domain.Grn;
import in.societyos.vendor.receiving.domain.GrnLine;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
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

/** Goods received notes; each one publishes {@code vendor.grn.recorded} for inventory-service. */
@RestController
class GrnController {

  static final String VIEW = "@perm.hasAny('vendor:view', 'grn:record', 'po:create', 'invoice:approve')";

  private final GrnService grns;

  GrnController(GrnService grns) {
    this.grns = grns;
  }

  record LineRequest(@NotNull UUID poLineId, @PositiveOrZero int receivedQty, @PositiveOrZero Integer acceptedQty) {}

  record GrnRequest(@NotNull UUID poId, UUID storeId, LocalDate receivedOn, String challanRef, String note,
      @NotEmpty List<@Valid LineRequest> lines) {}

  record GrnLineResponse(UUID id, UUID poLineId, int receivedQty, int acceptedQty, int rejectedQty) {
    static GrnLineResponse from(GrnLine l) {
      return new GrnLineResponse(l.getId(), l.getPoLineId(), l.getReceivedQty(), l.getAcceptedQty(), l.rejectedQty());
    }
  }

  record GrnSummary(UUID id, String number, UUID poId, UUID storeId, LocalDate receivedOn) {
    static GrnSummary from(Grn g) {
      return new GrnSummary(g.getId(), g.getNumber(), g.getPoId(), g.getStoreId(), g.getReceivedOn());
    }
  }

  record GrnResponse(UUID id, String number, UUID poId, String poNumber, UUID storeId, LocalDate receivedOn,
      String challanRef, String note, List<GrnLineResponse> lines) {
    static GrnResponse from(GrnDetail d) {
      Grn g = d.grn();
      return new GrnResponse(g.getId(), g.getNumber(), g.getPoId(), d.poNumber(), g.getStoreId(), g.getReceivedOn(),
          g.getChallanRef(), g.getNote(), d.lines().stream().map(GrnLineResponse::from).toList());
    }
  }

  @PostMapping("/v1/grns")
  @PreAuthorize("@perm.has('grn:record')")
  @ResponseStatus(HttpStatus.CREATED)
  GrnResponse record(@Valid @RequestBody GrnRequest r) {
    List<LineCommand> lines = r.lines().stream()
        .map(l -> new LineCommand(l.poLineId(), l.receivedQty(), l.acceptedQty())).toList();
    return GrnResponse.from(grns.record(new GrnCommand(r.poId(), r.storeId(), r.receivedOn(), r.challanRef(), r.note(),
        lines)));
  }

  @GetMapping("/v1/grns")
  @PreAuthorize(VIEW)
  List<GrnSummary> list(@RequestParam(required = false) UUID poId) {
    return grns.list(poId).stream().map(GrnSummary::from).toList();
  }

  @GetMapping("/v1/grns/{id}")
  @PreAuthorize(VIEW)
  GrnResponse get(@PathVariable UUID id) {
    return GrnResponse.from(grns.get(id));
  }
}
