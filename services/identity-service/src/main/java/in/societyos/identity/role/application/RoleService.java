package in.societyos.identity.role.application;

import in.societyos.identity.role.domain.Role;
import in.societyos.identity.role.domain.RoleAssignment;
import in.societyos.identity.role.domain.RoleEvents.RoleAssigned;
import in.societyos.identity.role.domain.RoleEvents.RoleRevoked;
import in.societyos.identity.role.domain.RoleEvents.RoleUpdated;
import in.societyos.identity.role.infrastructure.RoleAssignmentRepository;
import in.societyos.identity.role.infrastructure.RoleRepository;
import in.societyos.identity.role.infrastructure.RoleTemplates;
import in.societyos.identity.user.domain.AppUser;
import in.societyos.identity.user.infrastructure.AppUserRepository;
import in.societyos.identity.platform.core.error.ProblemException;
import in.societyos.identity.platform.core.tenant.TenantContext;
import in.societyos.identity.platform.events.DomainEvents;
import in.societyos.identity.platform.security.RemotePermissionResolver;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Roles and role assignments of the active society. */
@Service
public class RoleService {

  private static final Logger log = LoggerFactory.getLogger(RoleService.class);

  public record RoleView(UUID id, String code, String name, List<String> permissions, boolean system) {
    static RoleView of(Role r) {
      return new RoleView(r.getId(), r.getCode(), r.getName(), r.getPermissions(), r.isSystem());
    }
  }

  public record PermissionView(String code, String module, String action, String description) {}

  public record AssignmentView(UUID id, UUID userId, String roleCode, String source, Instant validFrom) {}

  private final RoleRepository roles;
  private final RoleAssignmentRepository assignments;
  private final RoleTemplates templates;
  private final AppUserRepository users;
  private final DomainEvents events;
  private final StringRedisTemplate redis;

  public RoleService(
      RoleRepository roles,
      RoleAssignmentRepository assignments,
      RoleTemplates templates,
      AppUserRepository users,
      DomainEvents events,
      StringRedisTemplate redis) {
    this.roles = roles;
    this.assignments = assignments;
    this.templates = templates;
    this.users = users;
    this.events = events;
    this.redis = redis;
  }

  /** Copies the default role bundles into the active society (idempotent). */
  @Transactional
  public int provisionSociety() {
    UUID societyId = TenantContext.activeSocietyId();
    int created = 0;
    for (RoleTemplates.Template t : templates.all()) {
      if (roles.findBySocietyIdAndCode(societyId, t.code()).isEmpty()) {
        roles.save(Role.fromTemplate(t.code(), t.name(), t.permissions()));
        created++;
      }
    }
    log.info("Provisioned {} roles for society {}", created, societyId);
    return created;
  }

  @Transactional
  public AssignmentView assign(UUID userId, String roleCode, RoleAssignment.Source source, UUID sourceRef) {
    UUID societyId = TenantContext.activeSocietyId();
    AppUser user = users.findById(userId).orElseThrow(() -> ProblemException.notFound("User", userId));
    Role role = roleFor(societyId, roleCode);

    var existing = assignments.findBySocietyIdAndUserIdAndRoleIdAndRevokedAtIsNull(societyId, userId, role.getId());
    if (existing.isPresent()) {
      return view(existing.get(), role);
    }
    RoleAssignment a = assignments.save(RoleAssignment.of(userId, role.getId(), source, sourceRef));
    user.bumpPermissionVersion();
    if (user.getLastSocietyId() == null) {
      user.rememberSociety(societyId);
    }
    events.publish(new RoleAssigned(a.getId(), userId, role.getCode(), source.name()));
    evictPermissions(userId, societyId);
    return view(a, role);
  }

  @Transactional
  public void revoke(UUID assignmentId) {
    UUID societyId = TenantContext.activeSocietyId();
    RoleAssignment a =
        assignments.findById(assignmentId).orElseThrow(() -> ProblemException.notFound("RoleAssignment", assignmentId));
    revoke(a, societyId);
  }

  /** Revokes the assignments created from one source record (e.g. an ended flat membership). */
  @Transactional
  public int revokeBySource(UUID sourceRef) {
    UUID societyId = TenantContext.activeSocietyId();
    List<RoleAssignment> found = assignments.findBySocietyIdAndSourceRefAndRevokedAtIsNull(societyId, sourceRef);
    found.forEach(a -> revoke(a, societyId));
    return found.size();
  }

  private void revoke(RoleAssignment a, UUID societyId) {
    if (!a.isActive()) {
      return;
    }
    a.revoke();
    Role role = roles.findById(a.getRoleId()).orElseThrow();
    users.findById(a.getUserId()).ifPresent(AppUser::bumpPermissionVersion);
    events.publish(new RoleRevoked(a.getId(), a.getUserId(), role.getCode()));
    evictPermissions(a.getUserId(), societyId);
  }

  @Transactional(readOnly = true)
  public List<RoleView> roles() {
    return roles.findBySocietyIdOrderByCode(TenantContext.activeSocietyId()).stream().map(RoleView::of).toList();
  }

  @Transactional(readOnly = true)
  public List<AssignmentView> assignmentsOf(UUID userId) {
    UUID societyId = TenantContext.activeSocietyId();
    return assignments.findBySocietyIdAndUserIdAndRevokedAtIsNull(societyId, userId).stream()
        .map(a -> view(a, roles.findById(a.getRoleId()).orElseThrow()))
        .toList();
  }

  @Transactional
  public RoleView updatePermissions(String roleCode, Set<String> permissions) {
    UUID societyId = TenantContext.activeSocietyId();
    Set<String> known = templates.permissionCodes();
    Set<String> unknown = new TreeSet<>(permissions);
    unknown.removeAll(known);
    if (!unknown.isEmpty()) {
      throw ProblemException.badRequest("UNKNOWN_PERMISSION", "Unknown permissions: " + unknown);
    }
    Role role = roleFor(societyId, roleCode);
    role.replacePermissions(permissions);
    events.publish(new RoleUpdated(role.getId(), role.getCode(), role.getPermissions()));
    assignments.findBySocietyIdAndRoleIdAndRevokedAtIsNull(societyId, role.getId())
        .forEach(a -> evictPermissions(a.getUserId(), societyId));
    return RoleView.of(role);
  }

  @Transactional(readOnly = true)
  public Set<String> permissionsOf(UUID userId, UUID societyId) {
    return new TreeSet<>(assignments.permissionsOf(societyId, userId));
  }

  public List<PermissionView> catalogue() {
    return templates.permissions().stream()
        .map(p -> new PermissionView(p.code(), p.module(), p.action(), p.description()))
        .toList();
  }

  /** Society roles are provisioned on society.created; a missing one is created from its template. */
  private Role roleFor(UUID societyId, String code) {
    return roles.findBySocietyIdAndCode(societyId, code)
        .orElseGet(
            () -> {
              RoleTemplates.Template t =
                  templates.byCode(code).orElseThrow(() -> ProblemException.notFound("Role", code));
              return roles.save(Role.fromTemplate(t.code(), t.name(), t.permissions()));
            });
  }

  private AssignmentView view(RoleAssignment a, Role role) {
    return new AssignmentView(a.getId(), a.getUserId(), role.getCode(), a.getSource().name(), a.getValidFrom());
  }

  private void evictPermissions(UUID userId, UUID societyId) {
    try {
      redis.delete(RemotePermissionResolver.cacheKey(userId, societyId));
    } catch (RuntimeException e) {
      log.warn("Could not evict permission cache for {}: {}", userId, e.getMessage());
    }
  }
}
