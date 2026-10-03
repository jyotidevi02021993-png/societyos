package in.societyos.utility.checklist.domain;

import in.societyos.utility.platform.core.UuidV7;
import in.societyos.utility.platform.jpa.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;

/** The answer to one item of a completed run. */
@Entity
@Table(name = "checklist_response")
public class ChecklistResponse extends TenantEntity {

  @Column(name = "run_id", nullable = false, updatable = false)
  private UUID runId;

  @Column(name = "item_code", nullable = false, updatable = false)
  private String itemCode;

  @Column(name = "item_label", nullable = false, updatable = false)
  private String itemLabel;

  @Column(nullable = false, updatable = false)
  private String result;

  @Column(updatable = false)
  private BigDecimal value;

  @Column(name = "text_value", updatable = false)
  private String textValue;

  @Column(updatable = false)
  private String note;

  @Column(name = "photo_media_id", updatable = false)
  private UUID photoMediaId;

  protected ChecklistResponse() {}

  public ChecklistResponse(UUID runId, ChecklistRules.Scored s) {
    super(UuidV7.next());
    this.runId = runId;
    this.itemCode = s.item().code();
    this.itemLabel = s.item().label();
    this.result = s.result().name();
    this.value = s.value();
    this.textValue = s.textValue();
    this.note = s.note();
    this.photoMediaId = s.photoMediaId();
  }

  public UUID getRunId() { return runId; }
  public String getItemCode() { return itemCode; }
  public String getItemLabel() { return itemLabel; }
  public String getResult() { return result; }
  public BigDecimal getValue() { return value; }
  public String getTextValue() { return textValue; }
  public String getNote() { return note; }
  public UUID getPhotoMediaId() { return photoMediaId; }
}
