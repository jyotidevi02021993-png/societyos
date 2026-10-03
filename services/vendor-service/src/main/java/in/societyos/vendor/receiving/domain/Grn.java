package in.societyos.vendor.receiving.domain;

import in.societyos.vendor.platform.jpa.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.util.UUID;

/** A goods received note: one delivery against a purchase order (partial receipts allowed). */
@Entity
@Table(name = "grn")
public class Grn extends TenantEntity {

  @Column(nullable = false, updatable = false)
  private String number;
  @Column(name = "po_id", nullable = false, updatable = false)
  private UUID poId;
  @Column(name = "store_id", updatable = false)
  private UUID storeId;
  @Column(name = "received_on", nullable = false, updatable = false)
  private LocalDate receivedOn;
  @Column(name = "challan_ref", updatable = false)
  private String challanRef;
  @Column(columnDefinition = "text", updatable = false)
  private String note;

  protected Grn() {}

  public Grn(String number, UUID poId, UUID storeId, LocalDate receivedOn, String challanRef, String note) {
    getId();
    this.number = number;
    this.poId = poId;
    this.storeId = storeId;
    this.receivedOn = receivedOn;
    this.challanRef = challanRef;
    this.note = note;
  }

  public String getNumber() { return number; }
  public UUID getPoId() { return poId; }
  public UUID getStoreId() { return storeId; }
  public LocalDate getReceivedOn() { return receivedOn; }
  public String getChallanRef() { return challanRef; }
  public String getNote() { return note; }
}
