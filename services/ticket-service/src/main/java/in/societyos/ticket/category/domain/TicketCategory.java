package in.societyos.ticket.category.domain;

import in.societyos.ticket.platform.jpa.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * Complaint category with its routing (department, default technician or vendor) and an optional
 * provisional resolve time. The binding SLA policy lives in workflow-service.
 */
@Entity
@Table(name = "ticket_category")
public class TicketCategory extends TenantEntity {

  @Column(nullable = false)
  private String name;
  private String department;
  @Column(name = "default_priority", nullable = false)
  private String defaultPriority;
  @Column(name = "default_assignee_user_id")
  private UUID defaultAssigneeUserId;
  @Column(name = "default_vendor_id")
  private UUID defaultVendorId;
  @Column(name = "resolve_mins")
  private Integer resolveMins;
  @Column(nullable = false)
  private boolean active;

  protected TicketCategory() {}

  public TicketCategory(String name) {
    this.name = name;
    this.defaultPriority = "P3";
    this.active = true;
  }

  public void update(String name, String department, String defaultPriority, UUID defaultAssigneeUserId,
      UUID defaultVendorId, Integer resolveMins, boolean active) {
    if (resolveMins != null && resolveMins <= 0) {
      throw new IllegalArgumentException("resolveMins must be positive");
    }
    this.name = name;
    this.department = department;
    this.defaultPriority = defaultPriority;
    this.defaultAssigneeUserId = defaultAssigneeUserId;
    this.defaultVendorId = defaultVendorId;
    this.resolveMins = resolveMins;
    this.active = active;
  }

  /** True when a new complaint in this category can be turned into an assigned job card at once. */
  public boolean routesAutomatically() {
    return defaultAssigneeUserId != null || defaultVendorId != null;
  }

  public String getName() { return name; }
  public String getDepartment() { return department; }
  public String getDefaultPriority() { return defaultPriority; }
  public UUID getDefaultAssigneeUserId() { return defaultAssigneeUserId; }
  public UUID getDefaultVendorId() { return defaultVendorId; }
  public Integer getResolveMins() { return resolveMins; }
  public boolean isActive() { return active; }
}
