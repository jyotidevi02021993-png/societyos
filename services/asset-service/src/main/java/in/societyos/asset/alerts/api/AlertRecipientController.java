package in.societyos.asset.alerts.api;

import in.societyos.asset.alerts.application.AlertService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Users who receive warranty, AMC and PM alerts (otherwise estate/facility managers by role). */
@RestController
@RequestMapping("/v1/alert-recipients")
public class AlertRecipientController {

  private final AlertService alerts;

  public AlertRecipientController(AlertService alerts) {
    this.alerts = alerts;
  }

  public record Recipients(@NotNull @Size(max = 50) List<UUID> userIds) {}

  @GetMapping
  @PreAuthorize("@perm.has('asset:view')")
  public Recipients get() {
    return new Recipients(alerts.recipients());
  }

  @PutMapping
  @PreAuthorize("@perm.has('asset:manage')")
  public Recipients replace(@Valid @RequestBody Recipients r) {
    return new Recipients(alerts.replaceRecipients(r.userIds()));
  }
}
