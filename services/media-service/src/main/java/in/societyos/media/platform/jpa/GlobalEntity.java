package in.societyos.media.platform.jpa;

import in.societyos.media.platform.core.UuidV7;
import in.societyos.media.platform.core.tenant.TenantContext;
import jakarta.persistence.Column;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

/**
 * Base class for the few entities that do not belong to one society (e.g. {@code app_user}: a
 * person can live in several societies). Such tables have no RLS; every use must be reviewed.
 */
@MappedSuperclass
public abstract class GlobalEntity {

  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Version
  @Column(name = "version", nullable = false)
  private Long version;

  protected GlobalEntity() {}

  @PrePersist
  void onPersist() {
    if (id == null) {
      id = UuidV7.next();
    }
    createdAt = Instant.now();
    updatedAt = createdAt;
  }

  @PreUpdate
  void onUpdate() {
    updatedAt = Instant.now();
  }

  public UUID getId() {
    if (id == null) {
      id = UuidV7.next();
    }
    return id;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  public Long getVersion() {
    return version;
  }

  /** Current user, for created_by style columns on global tables. */
  protected static UUID currentUser() {
    return TenantContext.userId().orElse(null);
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    return id != null && id.equals(((GlobalEntity) o).id);
  }

  @Override
  public int hashCode() {
    return getClass().hashCode();
  }
}
