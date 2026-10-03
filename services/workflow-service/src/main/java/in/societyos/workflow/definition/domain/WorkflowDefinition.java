package in.societyos.workflow.definition.domain;

import in.societyos.workflow.platform.jpa.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.hibernate.annotations.ColumnTransformer;

/**
 * A versioned approval workflow for one kind of subject (JOBCARD, PO, EXPENSE, …). Editing creates
 * a new version; running instances keep the version they started with.
 */
@Entity
@Table(name = "workflow_definition")
public class WorkflowDefinition extends TenantEntity {

  @Column(nullable = false, updatable = false)
  private String kind;
  @Column(nullable = false)
  private String name;
  @Column(name = "def_version", nullable = false, updatable = false)
  private int defVersion;
  @Column(name = "definition", nullable = false, updatable = false, columnDefinition = "jsonb")
  @ColumnTransformer(write = "?::jsonb")
  private String definitionJson;
  @Column(nullable = false)
  private boolean active;

  protected WorkflowDefinition() {}

  public WorkflowDefinition(String kind, String name, int defVersion, String definitionJson) {
    this.kind = kind;
    this.name = name;
    this.defVersion = defVersion;
    this.definitionJson = definitionJson;
    this.active = true;
  }

  public void retire() {
    this.active = false;
  }

  public String getKind() { return kind; }
  public String getName() { return name; }
  public int getDefVersion() { return defVersion; }
  public String getDefinitionJson() { return definitionJson; }
  public boolean isActive() { return active; }
}
