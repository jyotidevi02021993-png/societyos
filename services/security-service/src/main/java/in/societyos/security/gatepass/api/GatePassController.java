package in.societyos.security.gatepass.api;

import in.societyos.security.gatepass.application.GatePassService;
import in.societyos.security.gatepass.application.GatePassService.PassView;
import in.societyos.security.gatepass.application.GatePassService.Share;
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

/** Gate passes. Own-flat checks happen in the use case; guards see passes without the code. */
@RestController
@RequestMapping("/v1/gatepasses")
class GatePassController {

  private final GatePassService passes;

  GatePassController(GatePassService passes) {
    this.passes = passes;
  }

  record CreatePass(@NotNull UUID flatId, @NotBlank String kind, @Size(max = 120) String guestName,
      Instant validFrom, @NotNull Instant validTo, Integer maxUses) {}

  record VerifyPass(String code, String qrToken) {}

  /** {@code code} and {@code qrToken} are only returned to residents of the pass flat. */
  record PassResponse(UUID id, UUID flatId, String flatLabel, String kind, String guestName, Instant validFrom,
      Instant validTo, int maxUses, int usedCount, int usesLeft, String status, String usableNow, String code,
      String qrToken) {
    static PassResponse of(PassView v) {
      boolean withSecrets = v.mine();
      var p = v.pass();
      return new PassResponse(p.getId(), p.getFlatId(), v.flatLabel(), p.getKind(), p.getGuestName(),
          p.getValidFrom(), p.getValidTo(), p.getMaxUses(), p.getUsedCount(), p.usesLeft(), p.getStatus(),
          v.rejection() == null ? "YES" : v.rejection().name(), withSecrets ? p.getCode() : null,
          withSecrets ? p.getQrToken() : null);
    }
  }

  @PostMapping
  @PreAuthorize("@perm.has('gatepass:create')")
  ResponseEntity<PassResponse> create(@Valid @RequestBody CreatePass r) {
    PassView v = passes.create(r.flatId(), r.kind().trim().toUpperCase(), r.guestName(), r.validFrom(), r.validTo(),
        r.maxUses());
    return ResponseEntity.created(URI.create("/v1/gatepasses/" + v.pass().getId())).body(PassResponse.of(v));
  }

  @GetMapping
  @PreAuthorize("@perm.hasAny('gatepass:view', 'gate:entry', 'gate:log-view')")
  List<PassResponse> list(@RequestParam(required = false) UUID flatId) {
    return passes.list(flatId).stream().map(v -> PassResponse.of(v)).toList();
  }

  @GetMapping("/{id}")
  @PreAuthorize("@perm.hasAny('gatepass:view', 'gate:entry', 'gate:log-view')")
  PassResponse get(@PathVariable UUID id) {
    return PassResponse.of(passes.get(id));
  }

  @GetMapping("/{id}/share")
  @PreAuthorize("@perm.has('gatepass:create')")
  Share share(@PathVariable UUID id) {
    return passes.share(id);
  }

  @PostMapping("/{id}/cancel")
  @PreAuthorize("@perm.has('gatepass:create')")
  PassResponse cancel(@PathVariable UUID id) {
    return PassResponse.of(passes.cancel(id));
  }

  /** Guard checks a code or QR without using it (entry: {@code POST /v1/entries/pass}). */
  @PostMapping("/verify")
  @PreAuthorize("@perm.has('gate:entry')")
  PassResponse verify(@RequestBody VerifyPass r) {
    return PassResponse.of(passes.verify(r.code(), r.qrToken()));
  }

  record EdgePass(UUID id, UUID flatId, String kind, String guestName, String code, String qrToken,
      Instant validFrom, Instant validTo, int usesLeft, String status, Instant updatedAt) {}

  record EdgePasses(List<EdgePass> passes, Instant nextSince) {}

  /**
   * Edge agent delta pull: passes changed since {@code since}, with codes so the gate can admit
   * pass holders offline. Only the edge agent (service token, or {@code gate:edge-sync}) may call it.
   */
  @GetMapping("/edge")
  @PreAuthorize("@perm.has('gate:edge-sync')")
  EdgePasses edge(@RequestParam(required = false) Instant since, @RequestParam(required = false) Integer limit) {
    int max = limit == null || limit <= 0 ? 500 : Math.min(limit, 2000);
    var changed = passes.changedSince(since, max);
    List<EdgePass> items = changed.stream().map(p -> new EdgePass(p.getId(), p.getFlatId(), p.getKind(),
        p.getGuestName(), p.getCode(), p.getQrToken(), p.getValidFrom(), p.getValidTo(), p.usesLeft(), p.getStatus(),
        p.getUpdatedAt())).toList();
    return new EdgePasses(items, items.isEmpty() ? since : items.getLast().updatedAt());
  }
}
