package in.societyos.workflow.sla.domain;

import in.societyos.workflow.platform.jpa.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.hibernate.annotations.ColumnTransformer;

/**
 * Respond / resolve times per subject type × category × priority (NULL category or priority = any)
 * and the escalation chain after a breach.
 */
@Entity
@Table(name = "sla_policy")
public class SlaPolicy extends TenantEntity {

  @Column(name = "subject_type", nullable = false)
  private String subjectType;
  @Column(name = "category_name")
  private String categoryName;
  private String priority;
  @Column(name = "respond_mins")
  private Integer respondMins;
  @Column(name = "resolve_mins", nullable = false)
  private int resolveMins;
  @Column(name = "warn_percent", nullable = false)
  private int warnPercent;
  @Column(name = "escalation_chain", nullable = false, columnDefinition = "jsonb")
  @ColumnTransformer(write = "?::jsonb")
  private String escalationChainJson;
  @Column(nullable = false)
  private boolean active;

  protected SlaPolicy() {}

  public SlaPolicy(String subjectType) {
    this.subjectType = subjectType;
    this.active = true;
    this.warnPercent = 80;
    this.escalationChainJson = "[]";
  }

  public void update(String subjectType, String categoryName, String priority, Integer respondMins, int resolveMins,
      int warnPercent, String escalationChainJson, boolean active) {
    if (resolveMins <= 0 || respondMins != null && respondMins <= 0) {
      throw new IllegalArgumentException("Times must be positive minutes");
    }
    if (respondMins != null && respondMins > resolveMins) {
      throw new IllegalArgumentException("respondMins cannot exceed resolveMins");
    }
    if (warnPercent < 1 || warnPercent > 99) {
      throw new IllegalArgumentException("warnPercent must be 1..99");
    }
    this.subjectType = subjectType;
    this.categoryName = categoryName;
    this.priority = priority;
    this.respondMins = respondMins;
    this.resolveMins = resolveMins;
    this.warnPercent = warnPercent;
    this.escalationChainJson = escalationChainJson;
    this.active = active;
  }

  /** How specific the policy is for a lookup: category and priority matches beat wildcards. */
  public int specificity() {
    return (categoryName != null ? 2 : 0) + (priority != null ? 1 : 0);
  }

  public boolean matches(String category, String prio) {
    return active
        && (categoryName == null || category != null && categoryName.equalsIgnoreCase(category))
        && (priority == null || priority.equals(prio));
  }

  public String getSubjectType() { return subjectType; }
  public String getCategoryName() { return categoryName; }
  public String getPriority() { return priority; }
  public Integer getRespondMins() { return respondMins; }
  public int getResolveMins() { return resolveMins; }
  public int getWarnPercent() { return warnPercent; }
  public String getEscalationChainJson() { return escalationChainJson; }
  public boolean isActive() { return active; }
}
