package in.societyos.vendor.vendor.api;

import in.societyos.vendor.vendor.application.VendorService;
import in.societyos.vendor.vendor.application.VendorService.AgentCommand;
import in.societyos.vendor.vendor.application.VendorService.VendorCommand;
import in.societyos.vendor.vendor.application.VendorService.VendorView;
import in.societyos.vendor.vendor.domain.Agent;
import in.societyos.vendor.vendor.domain.Vendor;
import in.societyos.vendor.vendor.domain.VendorKycDocument;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Vendor master, KYC documents, ratings and agents. */
@RestController
class VendorController {

  static final String VIEW = "@perm.hasAny('vendor:view', 'vendor:manage', 'po:create', 'po:approve')";
  static final String MANAGE = "@perm.has('vendor:manage')";

  private final VendorService vendors;

  VendorController(VendorService vendors) {
    this.vendors = vendors;
  }

  record VendorRequest(String code, @NotBlank String name, @NotBlank String category, List<String> workScopes,
      String gstin, String pan, String contactName, String contactPhone, String contactEmail, String address,
      String agreementRef, LocalDate agreementValidUntil, String riskLevel) {
    VendorCommand command() {
      return new VendorCommand(code, name, category, workScopes, gstin, pan, contactName, contactPhone, contactEmail,
          address, agreementRef, agreementValidUntil, riskLevel);
    }
  }

  record StatusRequest(@NotBlank String status) {}

  record KycRequest(@NotBlank String kind, @NotNull UUID mediaId, LocalDate validUntil) {}

  record RatingRequest(@Min(1) @Max(5) int score, UUID poId, String comment) {}

  record AgentRequest(UUID vendorId, UUID userId, @NotBlank String code, @NotBlank String name, @NotBlank String role,
      List<String> skills) {}

  record KycResponse(UUID id, String kind, UUID mediaId, LocalDate validUntil, Instant verifiedAt) {
    static KycResponse from(VendorKycDocument d) {
      return new KycResponse(d.getId(), d.getKind(), d.getMediaId(), d.getValidUntil(), d.getVerifiedAt());
    }
  }

  record VendorSummary(UUID id, String code, String name, String category, List<String> workScopes, String status,
      String riskLevel, Double rating, int ratingCount) {
    static VendorSummary from(Vendor v) {
      return new VendorSummary(v.getId(), v.getCode(), v.getName(), v.getCategory(), v.getWorkScopes(), v.getStatus(),
          v.getRiskLevel(), v.rating(), v.getRatingCount());
    }
  }

  record VendorResponse(UUID id, String code, String name, String category, List<String> workScopes, String gstin,
      String panMasked, String contactName, String contactPhoneMasked, String contactEmail, String address,
      String agreementRef, LocalDate agreementValidUntil, String riskLevel, String status, Double rating,
      int ratingCount, List<KycResponse> kyc) {
    static VendorResponse from(VendorView view) {
      Vendor v = view.vendor();
      return new VendorResponse(v.getId(), v.getCode(), v.getName(), v.getCategory(), v.getWorkScopes(), v.getGstin(),
          view.panMasked(), v.getContactName(), view.contactPhoneMasked(), view.contactEmail(), v.getAddress(),
          v.getAgreementRef(), v.getAgreementValidUntil(), v.getRiskLevel(), v.getStatus(), v.rating(),
          v.getRatingCount(), view.kyc().stream().map(KycResponse::from).toList());
    }
  }

  record AgentResponse(UUID id, UUID vendorId, UUID userId, String code, String name, String role, List<String> skills,
      String status) {
    static AgentResponse from(Agent a) {
      return new AgentResponse(a.getId(), a.getVendorId(), a.getUserId(), a.getCode(), a.getName(), a.getRole(),
          a.getSkills(), a.getStatus());
    }
  }

  @GetMapping("/v1/vendors")
  @PreAuthorize(VIEW)
  List<VendorSummary> list(@RequestParam(required = false) String category) {
    return vendors.list(category).stream().map(VendorSummary::from).toList();
  }

  @PostMapping("/v1/vendors")
  @PreAuthorize(MANAGE)
  @ResponseStatus(HttpStatus.CREATED)
  VendorResponse register(@Valid @RequestBody VendorRequest r) {
    return VendorResponse.from(vendors.register(r.command()));
  }

  @GetMapping("/v1/vendors/{id}")
  @PreAuthorize(VIEW)
  VendorResponse get(@PathVariable UUID id) {
    return VendorResponse.from(vendors.get(id));
  }

  @PutMapping("/v1/vendors/{id}")
  @PreAuthorize(MANAGE)
  VendorResponse update(@PathVariable UUID id, @Valid @RequestBody VendorRequest r) {
    return VendorResponse.from(vendors.update(id, r.command()));
  }

  @PutMapping("/v1/vendors/{id}/status")
  @PreAuthorize(MANAGE)
  VendorResponse status(@PathVariable UUID id, @Valid @RequestBody StatusRequest r) {
    return VendorResponse.from(vendors.changeStatus(id, r.status()));
  }

  @PostMapping("/v1/vendors/{id}/kyc-documents")
  @PreAuthorize(MANAGE)
  @ResponseStatus(HttpStatus.CREATED)
  KycResponse attachKyc(@PathVariable UUID id, @Valid @RequestBody KycRequest r) {
    return KycResponse.from(vendors.attachKyc(id, r.kind(), r.mediaId(), r.validUntil()));
  }

  @PostMapping("/v1/vendors/{id}/kyc-documents/{documentId}/verify")
  @PreAuthorize(MANAGE)
  KycResponse verifyKyc(@PathVariable UUID id, @PathVariable UUID documentId) {
    return KycResponse.from(vendors.verifyKyc(id, documentId));
  }

  @PostMapping("/v1/vendors/{id}/ratings")
  @PreAuthorize("@perm.hasAny('vendor:manage', 'po:create')")
  VendorResponse rate(@PathVariable UUID id, @Valid @RequestBody RatingRequest r) {
    return VendorResponse.from(vendors.rate(id, r.score(), r.poId(), r.comment()));
  }

  @GetMapping("/v1/agents")
  @PreAuthorize(VIEW)
  List<AgentResponse> agents(@RequestParam(required = false) UUID vendorId) {
    return vendors.agents(vendorId).stream().map(AgentResponse::from).toList();
  }

  @PostMapping("/v1/agents")
  @PreAuthorize(MANAGE)
  @ResponseStatus(HttpStatus.CREATED)
  AgentResponse createAgent(@Valid @RequestBody AgentRequest r) {
    return AgentResponse.from(vendors.createAgent(new AgentCommand(r.vendorId(), r.userId(), r.code(), r.name(),
        r.role(), r.skills())));
  }

  @PostMapping("/v1/agents/{id}/deactivate")
  @PreAuthorize(MANAGE)
  AgentResponse deactivate(@PathVariable UUID id) {
    return AgentResponse.from(vendors.deactivateAgent(id));
  }
}
