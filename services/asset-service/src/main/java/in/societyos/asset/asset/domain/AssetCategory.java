package in.societyos.asset.asset.domain;

import in.societyos.asset.platform.core.UuidV7;
import in.societyos.asset.platform.jpa.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.hibernate.annotations.ColumnTransformer;

/** A society's own sub-category under a fixed group, e.g. "DG set" under ELECTRICAL. */
@Entity
@Table(name = "asset_category")
public class AssetCategory extends TenantEntity {

  @Column(name = "category_group", nullable = false)
  private String group;

  @Column(nullable = false, updatable = false)
  private String code;

  @Column(nullable = false)
  private String name;

  /** Spec fields the equipment form shows for this category, e.g. ["kva","fuelType"]. */
  @Column(name = "spec_fields", nullable = false, columnDefinition = "jsonb")
  @ColumnTransformer(write = "?::jsonb")
  private String specFieldsJson;

  protected AssetCategory() {}

  public AssetCategory(String group, String code, String name, String specFieldsJson) {
    super(UuidV7.next());
    if (!Asset.CATEGORY_GROUPS.contains(group)) {
      throw new IllegalArgumentException("group must be one of " + Asset.CATEGORY_GROUPS);
    }
    this.group = group;
    this.code = code;
    rename(name, specFieldsJson);
  }

  public void rename(String name, String specFieldsJson) {
    this.name = name;
    this.specFieldsJson = specFieldsJson == null || specFieldsJson.isBlank() ? "[]" : specFieldsJson;
  }

  public String getGroup() { return group; }
  public String getCode() { return code; }
  public String getName() { return name; }
  public String getSpecFieldsJson() { return specFieldsJson; }
}
