package in.societyos.identity.user.api;

import in.societyos.identity.user.application.UserService;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * Service-only endpoints: callable only with a service token that holds the scope. Not meant
 * for the public gateway (block {@code /api/identity/v1/internal/**} at the edge).
 */
@RestController
class InternalUserController {

  private final UserService users;

  InternalUserController(UserService users) {
    this.users = users;
  }

  /** Recipient contact for notification delivery. */
  @GetMapping("/v1/internal/users/{id}/contact")
  @PreAuthorize("hasAuthority('SCOPE_users:contact')")
  UserService.Contact contact(@PathVariable UUID id) {
    return users.contact(id);
  }
}
