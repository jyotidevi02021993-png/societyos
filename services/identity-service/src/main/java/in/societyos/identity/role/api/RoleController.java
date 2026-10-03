package in.societyos.identity.role.api;

import in.societyos.identity.role.application.RoleService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.Set;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
class RoleController {

  private final RoleService roles;

  RoleController(RoleService roles) {
    this.roles = roles;
  }

  record PermissionsUpdate(@NotNull Set<String> permissions) {}

  @GetMapping("/v1/roles")
  @PreAuthorize("@perm.hasAny('role:manage', 'user:manage')")
  List<RoleService.RoleView> list() {
    return roles.roles();
  }

  @PutMapping("/v1/roles/{code}/permissions")
  @PreAuthorize("@perm.has('role:manage')")
  RoleService.RoleView update(@PathVariable String code, @Valid @RequestBody PermissionsUpdate req) {
    return roles.updatePermissions(code, req.permissions());
  }

  @GetMapping("/v1/permissions")
  @PreAuthorize("@perm.has('role:manage')")
  List<RoleService.PermissionView> catalogue() {
    return roles.catalogue();
  }
}
