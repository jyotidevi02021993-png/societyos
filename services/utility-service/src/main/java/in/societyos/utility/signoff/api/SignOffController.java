package in.societyos.utility.signoff.api;

import in.societyos.utility.signoff.application.SignOffService;
import in.societyos.utility.signoff.domain.ManagerSignOff;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** {@code /v1/daily-summary} and {@code /v1/signoffs}. */
@RestController
public class SignOffController {

  private final SignOffService signoffs;
  private final JsonMapper json;

  public SignOffController(SignOffService signoffs, JsonMapper json) {
    this.signoffs = signoffs;
    this.json = json;
  }

  public record SignOffRequest(LocalDate date, @Size(max = 2000) String remarks, Boolean acknowledgePending) {}

  public record SignOffResponse(UUID id, LocalDate date, UUID managerUserId, String remarks, Instant signedAt,
      JsonNode summary) {}

  @GetMapping("/v1/daily-summary")
  @PreAuthorize("@perm.hasAny('signoff:daily', 'dashboard:view', 'checklist:manage')")
  public SignOffService.DaySummary summary(
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
    return signoffs.summary(date);
  }

  @GetMapping("/v1/signoffs")
  @PreAuthorize("@perm.hasAny('signoff:daily', 'dashboard:view')")
  public List<SignOffResponse> list(
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
    return signoffs.list(from, to).stream().map(this::response).toList();
  }

  @PostMapping("/v1/signoffs")
  @PreAuthorize("@perm.has('signoff:daily')")
  public ResponseEntity<SignOffResponse> signOff(@Valid @RequestBody SignOffRequest r) {
    SignOffResponse saved = response(signoffs.signOff(r.date(), r.remarks(), Boolean.TRUE.equals(r.acknowledgePending())));
    return ResponseEntity.created(URI.create("/v1/signoffs/" + saved.id())).body(saved);
  }

  private SignOffResponse response(ManagerSignOff s) {
    return new SignOffResponse(s.getId(), s.getSignDate(), s.getManagerUserId(), s.getRemarks(), s.getSignedAt(),
        json.readTree(s.getSummaryJson()));
  }
}
