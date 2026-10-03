package in.societyos.security.visitor.api;

import in.societyos.security.platform.core.error.ProblemException;
import in.societyos.security.visitor.application.VisitorService;
import in.societyos.security.visitor.application.VisitorService.VisitorView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/visitors")
class VisitorController {

  private final VisitorService visitors;

  VisitorController(VisitorService visitors) {
    this.visitors = visitors;
  }

  record LookupRequest(@NotBlank String phone) {}

  /** Returning visitor lookup (POST keeps the phone out of URLs and logs). Response phone is masked. */
  @PostMapping("/lookup")
  @PreAuthorize("@perm.has('gate:entry')")
  VisitorView lookup(@Valid @RequestBody LookupRequest r) {
    return visitors.lookup(r.phone()).orElseThrow(() -> ProblemException.notFound("visitor", "with this phone"));
  }
}
