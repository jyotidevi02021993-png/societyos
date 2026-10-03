package in.societyos.ticket.category.api;

import in.societyos.ticket.category.application.CategoryService;
import in.societyos.ticket.category.application.CategoryService.CategoryCommand;
import in.societyos.ticket.category.domain.TicketCategory;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.net.URI;
import java.util.List;
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

/** Complaint categories and their routing. Everyone in the society can list them (resident app picker). */
@RestController
@RequestMapping("/v1/categories")
class CategoryController {

  private final CategoryService categories;

  CategoryController(CategoryService categories) {
    this.categories = categories;
  }

  record CategoryRequest(@NotBlank String name, String department, String defaultPriority,
      UUID defaultAssigneeUserId, UUID defaultVendorId, Integer resolveMins, Boolean active) {
    CategoryCommand command() {
      return new CategoryCommand(name, department, defaultPriority, defaultAssigneeUserId, defaultVendorId,
          resolveMins, active);
    }
  }

  record CategoryResponse(UUID id, String name, String department, String defaultPriority,
      UUID defaultAssigneeUserId, UUID defaultVendorId, Integer resolveMins, boolean active) {
    static CategoryResponse from(TicketCategory c) {
      return new CategoryResponse(c.getId(), c.getName(), c.getDepartment(), c.getDefaultPriority(),
          c.getDefaultAssigneeUserId(), c.getDefaultVendorId(), c.getResolveMins(), c.isActive());
    }
  }

  @GetMapping
  @PreAuthorize("isAuthenticated()")
  List<CategoryResponse> list() {
    return categories.list().stream().map(CategoryResponse::from).toList();
  }

  @PostMapping
  @PreAuthorize("@perm.has('complaint:manage')")
  ResponseEntity<CategoryResponse> create(@Valid @RequestBody CategoryRequest r) {
    TicketCategory c = categories.create(r.command());
    return ResponseEntity.created(URI.create("/v1/categories/" + c.getId())).body(CategoryResponse.from(c));
  }

  @PutMapping("/{id}")
  @PreAuthorize("@perm.has('complaint:manage')")
  CategoryResponse update(@PathVariable UUID id, @Valid @RequestBody CategoryRequest r) {
    return CategoryResponse.from(categories.update(id, r.command()));
  }
}
