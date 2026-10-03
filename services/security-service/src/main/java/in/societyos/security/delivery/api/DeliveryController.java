package in.societyos.security.delivery.api;

import in.societyos.security.delivery.application.DeliveryService;
import in.societyos.security.delivery.application.DeliveryService.DeliveryView;
import in.societyos.security.delivery.application.DeliveryService.NewDelivery;
import in.societyos.security.delivery.domain.Delivery;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/deliveries")
class DeliveryController {

  private final DeliveryService deliveries;

  DeliveryController(DeliveryService deliveries) {
    this.deliveries = deliveries;
  }

  record ReceiveDelivery(@NotNull UUID flatId, @NotBlank @Size(max = 80) String company, boolean leaveAtGate,
      UUID gateId, @Size(max = 120) String personName, @Size(max = 20) String personPhone, UUID photoMediaId) {}

  record DeliveryResponse(UUID id, UUID flatId, String flatLabel, String company, boolean leaveAtGate, String status,
      UUID entryId, String entryStatus, UUID gateId, Instant receivedAt, Instant collectedAt) {
    static DeliveryResponse from(DeliveryView v) {
      Delivery d = v.delivery();
      return new DeliveryResponse(d.getId(), d.getFlatId(), v.flatLabel(), d.getCompany(), d.isLeaveAtGate(),
          d.getStatus(), d.getEntryId(), v.entry() == null ? null : v.entry().entry().getStatus(), d.getGateId(),
          d.getReceivedAt(), d.getCollectedAt());
    }
  }

  @PostMapping
  @PreAuthorize("@perm.has('gate:entry')")
  ResponseEntity<DeliveryResponse> receive(@Valid @RequestBody ReceiveDelivery r) {
    DeliveryView v = deliveries.receive(new NewDelivery(r.flatId(), r.company(), r.leaveAtGate(), r.gateId(),
        r.personName(), r.personPhone(), r.photoMediaId()));
    return ResponseEntity.created(URI.create("/v1/deliveries/" + v.delivery().getId())).body(DeliveryResponse.from(v));
  }

  @PostMapping("/{id}/collect")
  @PreAuthorize("@perm.hasAny('gate:entry', 'gatepass:view')")
  DeliveryResponse collect(@PathVariable UUID id) {
    return DeliveryResponse.from(deliveries.collect(id));
  }

  @GetMapping
  @PreAuthorize("@perm.hasAny('gate:entry', 'gate:log-view', 'gatepass:view')")
  List<DeliveryResponse> list(@RequestParam(required = false) UUID flatId,
      @RequestParam(required = false) String status) {
    return deliveries.list(flatId, status).stream().map(DeliveryResponse::from).toList();
  }
}
