package in.societyos.identity.platform.jpa;

import in.societyos.identity.platform.core.UuidV7;
import in.societyos.identity.platform.core.tenant.TenantContext;
import jakarta.persistence.Column;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

/**
 * Base class for every tenant-owned entity: UUIDv7 id, {@code society_id}, audit columns and an
 * optimistic-lock version. {@code society_id} is filled from {@link TenantContext} on insert and
 * never changes; PostgreSQL RLS enforces it again in the database.
 */
@MappedSuperclass
public abstract class TenantEntity {

  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "society_id", nullable = false, updatable = false)
  private UUID societyId;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "created_by", updatable = false)
  private UUID createdBy;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Column(name = "updated_by")
  private UUID updatedBy;

  /** Null until first persisted; Spring Data uses that to choose persist over merge. */
  @Version
  @Column(name = "version", nullable = false)
  private Long version;

  protected TenantEntity() {}

  /** Assigns the id now, so domain events can reference it before the entity is saved. */
  protected TenantEntity(UUID id) {
    this.id = id;
  }

  @PrePersist
  void onPersist() {
    if (id == null) {
      id = UuidV7.next();
    }
    if (societyId == null) {
      societyId = TenantContext.activeSocietyId();
    }
    Instant now = Instant.now();
    createdAt = now;
    updatedAt = now;
    createdBy = TenantContext.userId().orElse(null);
    updatedBy = createdBy;
  }

  @PreUpdate
  void onUpdate() {
    updatedAt = Instant.now();
    updatedBy = TenantContext.userId().orElse(null);
  }

  /** Returns the id, generating it first if needed (useful before {@code save}). */
  public UUID getId() {
    if (id == null) {
      id = UuidV7.next();
    }
    return id;
  }

  public UUID getSocietyId() {
    if (societyId == null) {
      societyId = TenantContext.activeSocietyId();
    }
    return societyId;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public UUID getCreatedBy() {
    return createdBy;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  public UUID getUpdatedBy() {
    return updatedBy;
  }

  public Long getVersion() {
    return version;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    return id != null && id.equals(((TenantEntity) o).id);
  }

  @Override
  public int hashCode() {
    return getClass().hashCode();
  }
}
