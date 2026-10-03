package in.societyos.billing.charge.api;

import in.societyos.billing.charge.application.PendingChargeService;
import in.societyos.billing.charge.domain.PendingCharge;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** One-off charges for the next bill run (manual; bookings arrive from community events). */
@RestController
@RequestMapping("/v1/charges")
class ChargeController {

  private final PendingChargeService charges;

  ChargeController(PendingChargeService charges) {
    this.charges = charges;
  }

  record ChargeRequest(@NotNull UUID flatId, @NotBlank @Size(max = 200) String description,
      @Positive long amountPaise, Boolean gstApplicable) {}

  record ChargeResponse(UUID id, UUID flatId, String sourceType, UUID sourceRef, String description,
      long amountPaise, boolean gstApplicable, String status, UUID billId) {
    static ChargeResponse from(PendingCharge c) {
      return new ChargeResponse(c.getId(), c.getFlatId(), c.getSourceType(), c.getSourceRef(), c.getDescription(),
          c.getAmountPaise(), c.isGstApplicable(), c.getStatus(), c.getBillId());
    }
  }

  @GetMapping
  @PreAuthorize("@perm.hasAny('bill:view', 'bill:generate')")
  List<ChargeResponse> list(@RequestParam(required = false) UUID flatId) {
    return charges.list(flatId).stream().map(ChargeResponse::from).toList();
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  @PreAuthorize("@perm.has('bill:generate')")
  ChargeResponse add(@Valid @RequestBody ChargeRequest r) {
    return ChargeResponse.from(charges.addManual(r.flatId(), r.description(), r.amountPaise(),
        Boolean.TRUE.equals(r.gstApplicable())));
  }

  @DeleteMapping("/{id}")
  @PreAuthorize("@perm.has('bill:generate')")
  ChargeResponse cancel(@PathVariable UUID id) {
    return ChargeResponse.from(charges.cancel(id));
  }
}
