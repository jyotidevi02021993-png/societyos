package in.societyos.asset.asset.application;

import in.societyos.asset.asset.domain.Asset;
import in.societyos.asset.asset.domain.AssetCategory;
import in.societyos.asset.asset.infrastructure.AssetCategoryRepository;
import in.societyos.asset.platform.core.error.ProblemException;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@Service
public class CategoryService {

  private final AssetCategoryRepository categories;
  private final JsonMapper json;

  public CategoryService(AssetCategoryRepository categories, JsonMapper json) {
    this.categories = categories;
    this.json = json;
  }

  public Set<String> groups() {
    return new TreeSet<>(Asset.CATEGORY_GROUPS);
  }

  @Transactional(readOnly = true)
  public List<AssetCategory> list() {
    return categories.findAllByOrderByGroupAscNameAsc();
  }

  @Transactional
  public AssetCategory create(String group, String code, String name, List<String> specFields) {
    String g = group.trim().toUpperCase(Locale.ROOT);
    String c = code.trim().toUpperCase(Locale.ROOT);
    if (categories.existsByCodeIgnoreCase(c)) {
      throw ProblemException.conflict("CATEGORY_CODE_EXISTS", "Category " + c + " already exists");
    }
    try {
      return categories.save(new AssetCategory(g, c, name.trim(), json.writeValueAsString(specFields == null ? List.of() : specFields)));
    } catch (IllegalArgumentException e) {
      throw ProblemException.badRequest("INVALID_CATEGORY", e.getMessage());
    }
  }

  @Transactional
  public AssetCategory update(UUID id, String name, List<String> specFields) {
    AssetCategory c = categories.findById(id).orElseThrow(() -> ProblemException.notFound("asset_category", id));
    c.rename(name.trim(), json.writeValueAsString(specFields == null ? List.of() : specFields));
    return categories.save(c);
  }

  @SuppressWarnings("unchecked")
  public List<String> specFields(AssetCategory c) {
    return json.readValue(c.getSpecFieldsJson(), List.class);
  }
}
