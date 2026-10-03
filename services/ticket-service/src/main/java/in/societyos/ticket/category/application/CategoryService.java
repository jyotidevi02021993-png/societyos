package in.societyos.ticket.category.application;

import in.societyos.ticket.category.domain.TicketCategory;
import in.societyos.ticket.category.infrastructure.TicketCategoryRepository;
import in.societyos.ticket.common.Priority;
import in.societyos.ticket.common.Texts;
import in.societyos.ticket.platform.core.error.ProblemException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CategoryService {

  public record CategoryCommand(String name, String department, String defaultPriority,
      UUID defaultAssigneeUserId, UUID defaultVendorId, Integer resolveMins, Boolean active) {}

  private final TicketCategoryRepository categories;

  public CategoryService(TicketCategoryRepository categories) {
    this.categories = categories;
  }

  @Transactional(readOnly = true)
  public List<TicketCategory> list() {
    return categories.findAllByOrderByNameAsc();
  }

  @Transactional(readOnly = true)
  public Optional<TicketCategory> find(UUID id) {
    return id == null ? Optional.empty() : categories.findById(id);
  }

  @Transactional(readOnly = true)
  public Optional<TicketCategory> findByName(String name) {
    return name == null || name.isBlank() ? Optional.empty() : categories.findByNameIgnoreCase(name.trim());
  }

  @Transactional
  public TicketCategory create(CategoryCommand c) {
    String name = requireName(c.name());
    if (categories.findByNameIgnoreCase(name).isPresent()) {
      throw ProblemException.conflict("CATEGORY_EXISTS", "A category named " + name + " already exists");
    }
    TicketCategory category = new TicketCategory(name);
    apply(category, name, c);
    return categories.save(category);
  }

  @Transactional
  public TicketCategory update(UUID id, CategoryCommand c) {
    TicketCategory category = categories.findById(id).orElseThrow(() -> ProblemException.notFound("category", id));
    String name = requireName(c.name());
    categories.findByNameIgnoreCase(name).filter(other -> !other.getId().equals(id)).ifPresent(other -> {
      throw ProblemException.conflict("CATEGORY_EXISTS", "A category named " + name + " already exists");
    });
    apply(category, name, c);
    return categories.save(category);
  }

  private static void apply(TicketCategory category, String name, CategoryCommand c) {
    try {
      category.update(name, Texts.clean(c.department()), Priority.of(c.defaultPriority(), "P3"),
          c.defaultAssigneeUserId(), c.defaultVendorId(), c.resolveMins(), c.active() == null || c.active());
    } catch (IllegalArgumentException e) {
      throw ProblemException.badRequest("INVALID_CATEGORY", e.getMessage());
    }
  }

  private static String requireName(String name) {
    String n = Texts.clean(name);
    if (n == null) {
      throw ProblemException.badRequest("NAME_REQUIRED", "name is required");
    }
    return n;
  }
}
