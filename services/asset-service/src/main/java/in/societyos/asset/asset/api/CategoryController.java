package in.societyos.asset.asset.api;

import in.societyos.asset.asset.application.CategoryService;
import in.societyos.asset.asset.domain.AssetCategory;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.List;
import java.util.Set;
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

@RestController
@RequestMapping("/v1/categories")
public class CategoryController {

  private final CategoryService categories;

  public CategoryController(CategoryService categories) {
    this.categories = categories;
  }

  public record CategoryRequest(@NotBlank String group, @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{2,30}") String code,
      @NotBlank @Size(max = 80) String name, List<String> specFields) {}

  public record CategoryUpdate(@NotBlank @Size(max = 80) String name, List<String> specFields) {}

  public record CategoryResponse(UUID id, String group, String code, String name, List<String> specFields) {}

  public record CategoriesResponse(Set<String> groups, List<CategoryResponse> categories) {}

  @GetMapping
  @PreAuthorize("@perm.has('asset:view')")
  public CategoriesResponse list() {
    return new CategoriesResponse(categories.groups(), categories.list().stream().map(this::response).toList());
  }

  @PostMapping
  @PreAuthorize("@perm.has('asset:manage')")
  public ResponseEntity<CategoryResponse> create(@Valid @RequestBody CategoryRequest r) {
    CategoryResponse saved = response(categories.create(r.group(), r.code(), r.name(), r.specFields()));
    return ResponseEntity.created(URI.create("/v1/categories/" + saved.id())).body(saved);
  }

  @PutMapping("/{id}")
  @PreAuthorize("@perm.has('asset:manage')")
  public CategoryResponse update(@PathVariable UUID id, @Valid @RequestBody CategoryUpdate r) {
    return response(categories.update(id, r.name(), r.specFields()));
  }

  private CategoryResponse response(AssetCategory c) {
    return new CategoryResponse(c.getId(), c.getGroup(), c.getCode(), c.getName(), categories.specFields(c));
  }
}
