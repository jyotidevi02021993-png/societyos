package in.societyos.society.member.domain;

import in.societyos.society.platform.core.UuidV7;
import in.societyos.society.platform.jpa.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;

/** A person in this society. Their phone lives in identity-service ({@code userId}), never here. */
@Entity
@Table(name = "resident")
public class Resident extends TenantEntity {

  @Column(name = "user_id", nullable = false, updatable = false)
  private UUID userId;

  @Column(nullable = false)
  private String name;

  @Column(name = "directory_opt_in", nullable = false)
  private boolean directoryOptIn;

  protected Resident() {}

  public Resident(UUID userId, String name) {
    super(UuidV7.next());
    this.userId = userId;
    this.name = name;
  }

  public void rename(String name) {
    if (name != null && !name.isBlank()) {
      this.name = name.trim();
    }
  }

  public void setDirectoryOptIn(boolean optIn) {
    this.directoryOptIn = optIn;
  }

  public UUID getUserId() { return userId; }
  public String getName() { return name; }
  public boolean isDirectoryOptIn() { return directoryOptIn; }
}
