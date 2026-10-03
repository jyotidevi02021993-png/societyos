package in.societyos.community.directory.domain;

import in.societyos.community.platform.jpa.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;

/** Flat → tower and label, from {@code society.flat.created/updated}: tower-targeted notices. */
@Entity
@Table(name = "flat_ref")
public class FlatRef extends TenantEntity {

  @Column(name = "tower_id") private UUID towerId;
  @Column(nullable = false) private String label;

  protected FlatRef() {}

  public FlatRef(UUID flatId, UUID towerId, String label) {
    super(flatId);
    apply(towerId, label);
  }

  public void apply(UUID towerId, String label) {
    this.towerId = towerId;
    this.label = label == null ? "" : label;
  }

  public UUID getTowerId() { return towerId; }
  public String getLabel() { return label; }
}
