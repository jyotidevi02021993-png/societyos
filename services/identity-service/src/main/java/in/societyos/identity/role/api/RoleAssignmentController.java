package in.societyos.identity.role.api;

import in.societyos.identity.role.application.RoleService;
import in.societyos.identity.role.domain.RoleAssignment;
import in.societyos.identity.user.application.UserService;
import in.societyos.identity.platform.core.error.ProblemException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Staff onboarding: assign GUARD, TECHNICIAN, ESTATE_MANAGER... by user id or by phone (invite). */
@RestController
@RequestMapping("/v1/role-assignments")
class RoleAssignmentController {

  private final RoleService roles;
  private final UserService users;

  RoleAssignmentController(RoleService roles, UserService users) {
    this.roles = roles;
    this.users = users;
  }

  record AssignRequest(
      UUID userId,
      @Pattern(regexp = "^\\+[1-9]\\d{7,14}$") String phone,
      @Size(max = 120) String name,
      @NotBlank String roleCode) {}

  @PostMapping
  @PreAuthorize("@perm.has('role:manage')")
  ResponseEntity<RoleService.AssignmentView> assign(@Valid @RequestBody AssignRequest req) {
    UUID userId = req.userId();
    if (userId == null) {
      if (req.phone() == null) {
        throw ProblemException.badRequest("USER_REQUIRED", "Give userId or phone");
      }
      userId = users.resolveByPhone(req.phone(), req.name());
    }
    var view = roles.assign(userId, req.roleCode(), RoleAssignment.Source.MANUAL, null);
    return ResponseEntity.status(HttpStatus.CREATED).body(view);
  }

  @GetMapping
  @PreAuthorize("@perm.hasAny('role:manage', 'user:manage')")
  List<RoleService.AssignmentView> list(@RequestParam UUID userId) {
    return roles.assignmentsOf(userId);
  }

  @DeleteMapping("/{id}")
  @PreAuthorize("@perm.has('role:manage')")
  ResponseEntity<Void> revoke(@PathVariable UUID id) {
    roles.revoke(id);
    return ResponseEntity.noContent().build();
  }
}
