package in.societyos.billing.tariff.api;

import in.societyos.billing.tariff.application.ChargeHeadService;
import in.societyos.billing.tariff.application.ChargeHeadService.HeadInput;
import in.societyos.billing.tariff.domain.ChargeHead;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/charge-heads")
class ChargeHeadController {

  private final ChargeHeadService heads;

  ChargeHeadController(ChargeHeadService heads) {
    this.heads = heads;
  }

  record HeadRequest(@Size(max = 30) String code, @NotBlank @Size(max = 100) String name, @NotBlank String basis,
      @PositiveOrZero long ratePaise, Map<String, Long> flatTypeRates, Boolean gstApplicable,
      Boolean appliesToVacant, Boolean active, Integer sortOrder) {
    HeadInput input() {
      return new HeadInput(name, basis, ratePaise, flatTypeRates, !Boolean.FALSE.equals(gstApplicable),
          !Boolean.FALSE.equals(appliesToVacant), !Boolean.FALSE.equals(active), sortOrder == null ? 0 : sortOrder);
    }
  }

  record HeadResponse(UUID id, String code, String name, String basis, long ratePaise, Map<String, Long> flatTypeRates,
      boolean gstApplicable, boolean appliesToVacant, boolean active, int sortOrder) {}

  private HeadResponse view(ChargeHead h) {
    return new HeadResponse(h.getId(), h.getCode(), h.getName(), h.getBasis().name(), h.getRatePaise(), heads.rates(h),
        h.isGstApplicable(), h.isAppliesToVacant(), h.isActive(), h.getSortOrder());
  }

  @GetMapping
  @PreAuthorize("@perm.hasAny('bill:view', 'bill:generate')")
  List<HeadResponse> list() {
    return heads.list().stream().map(this::view).toList();
  }

  @PostMapping
  @PreAuthorize("@perm.has('bill:generate')")
  ResponseEntity<HeadResponse> create(@Valid @RequestBody HeadRequest r) {
    ChargeHead saved = heads.create(r.code(), r.input());
    return ResponseEntity.created(URI.create("/v1/charge-heads/" + saved.getId())).body(view(saved));
  }

  @PutMapping("/{id}")
  @PreAuthorize("@perm.has('bill:generate')")
  HeadResponse update(@PathVariable UUID id, @Valid @RequestBody HeadRequest r) {
    return view(heads.update(id, r.input()));
  }
}
