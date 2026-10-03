package in.societyos.billing.bill.api;

import in.societyos.billing.bill.application.BillService;
import in.societyos.billing.bill.domain.Bill;
import in.societyos.billing.bill.domain.BillAdjustment;
import in.societyos.billing.bill.domain.BillLine;
import in.societyos.billing.platform.web.CursorPage;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Bills, adjustments and dues. Residents see only their own flats (checked in the use case). */
@RestController
class BillController {

  private final BillService bills;

  BillController(BillService bills) {
    this.bills = bills;
  }

  record BillSummary(UUID id, UUID flatId, String flatLabel, String number, String period, LocalDate billDate,
      LocalDate dueDate, long amountPaise, long gstPaise, long totalPaise, long lateFeePaise, long adjustmentPaise,
      long paidPaise, long balancePaise, long arrearsPaise, String status) {
    static BillSummary from(Bill b) {
      return new BillSummary(b.getId(), b.getFlatId(), b.getFlatLabel(), b.getNumber(), b.getPeriod(), b.getBillDate(),
          b.getDueDate(), b.getAmountPaise(), b.getGstPaise(), b.getTotalPaise(), b.getLateFeePaise(),
          b.getAdjustmentPaise(), b.getPaidPaise(), b.getBalancePaise(), b.getArrearsPaise(), b.getStatus());
    }
  }

  record LineResponse(String kind, String code, String description, long amountPaise, long gstPaise) {
    static LineResponse from(BillLine l) {
      return new LineResponse(l.getKind(), l.getCode(), l.getDescription(), l.getAmountPaise(), l.getGstPaise());
    }
  }

  record AdjustmentResponse(UUID id, UUID billId, String number, String kind, long amountPaise, String reason) {
    static AdjustmentResponse from(BillAdjustment a) {
      return new AdjustmentResponse(a.getId(), a.getBillId(), a.getNumber(), a.getKind(), a.getAmountPaise(),
          a.getReason());
    }
  }

  record BillDetailResponse(BillSummary bill, List<LineResponse> lines, List<AdjustmentResponse> adjustments) {}

  record AdjustmentRequest(@NotBlank String kind, @Positive long amountPaise, @NotBlank @Size(max = 300) String reason) {}

  @GetMapping("/v1/bills")
  @PreAuthorize("@perm.hasAny('bill:view', 'bill:view-own')")
  List<BillSummary> list(@RequestParam(required = false) UUID flatId, @RequestParam(required = false) String period,
      @RequestParam(required = false) String status, @RequestParam(required = false) Integer limit) {
    return bills.list(flatId, period, status, CursorPage.clampLimit(limit)).stream().map(BillSummary::from).toList();
  }

  @GetMapping("/v1/bills/{id}")
  @PreAuthorize("@perm.hasAny('bill:view', 'bill:view-own')")
  BillDetailResponse get(@PathVariable UUID id) {
    BillService.BillDetail d = bills.detail(id);
    return new BillDetailResponse(BillSummary.from(d.bill()), d.lines().stream().map(LineResponse::from).toList(),
        d.adjustments().stream().map(AdjustmentResponse::from).toList());
  }

  /** Credit note (CN-…) or debit note (DN-…) against a published bill. */
  @PostMapping("/v1/bills/{id}/adjustments")
  @ResponseStatus(HttpStatus.CREATED)
  @PreAuthorize("@perm.has('bill:generate')")
  AdjustmentResponse adjust(@PathVariable UUID id, @Valid @RequestBody AdjustmentRequest r) {
    return AdjustmentResponse.from(bills.adjust(id, r.kind(), r.amountPaise(), r.reason()));
  }

  @GetMapping("/v1/dues")
  @PreAuthorize("@perm.has('bill:view')")
  List<BillService.FlatDues> dues() {
    return bills.dues();
  }

  @GetMapping("/v1/me/dues")
  @PreAuthorize("@perm.hasAny('bill:view-own', 'bill:pay')")
  List<BillService.FlatDues> myDues() {
    return bills.myDues();
  }

  @GetMapping("/v1/flats/{flatId}/dues")
  @PreAuthorize("@perm.hasAny('bill:view', 'bill:view-own')")
  BillService.FlatDues flatDues(@PathVariable UUID flatId) {
    return bills.duesOf(flatId);
  }
}
