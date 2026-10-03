package in.societyos.identity.user.api;

import in.societyos.identity.user.application.UserService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/users")
class UserController {

  private final UserService users;

  UserController(UserService users) {
    this.users = users;
  }

  record ResolveRequest(
      @NotBlank @Pattern(regexp = "^\\+[1-9]\\d{7,14}$") String phone, @Size(max = 120) String name) {}

  record Resolved(UUID userId) {}

  /**
   * Phone → user id, pre-registering the person if needed. Called by society-service (OpenFeign)
   * when a resident or staff member is added, so events carry user ids and never phone numbers.
   */
  @PostMapping("/resolve")
  @PreAuthorize("@perm.hasAny('member:manage', 'user:manage', 'role:manage')")
  Resolved resolve(@Valid @RequestBody ResolveRequest req) {
    return new Resolved(users.resolveByPhone(req.phone(), req.name()));
  }
}
