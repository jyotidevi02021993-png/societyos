package in.societyos.asset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.societyos.asset.asset.domain.Asset;
import in.societyos.asset.coverage.domain.AmcContract;
import in.societyos.asset.coverage.domain.ExpiryAlert;
import in.societyos.asset.platform.core.UuidV7;
import in.societyos.asset.platform.core.tenant.Tenant;
import in.societyos.asset.platform.core.tenant.TenantContext;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AssetDomainRulesTest {

  @BeforeEach
  void tenant() {
    TenantContext.set(Tenant.system(UuidV7.next()));
  }

  @AfterEach
  void clear() {
    TenantContext.clear();
  }

  @Test
  void statusMachine() {
    Asset dg = asset();
    assertThat(dg.status()).isEqualTo(Asset.Status.WORKING);
    assertThat(dg.getQrToken()).hasSize(22);
    assertThat(dg.changeStatus(Asset.Status.BREAKDOWN)).isTrue();
    assertThat(dg.changeStatus(Asset.Status.BREAKDOWN)).isFalse();
    assertThat(dg.getBreakdownCount()).isEqualTo(1);
    dg.changeStatus(Asset.Status.UNDER_REPAIR);
    dg.changeStatus(Asset.Status.WORKING);
    dg.changeStatus(Asset.Status.DISPOSED);
    assertThat(dg.getQrToken()).as("disposed assets lose their QR").isNull();
    assertThatThrownBy(() -> dg.changeStatus(Asset.Status.WORKING)).isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(dg::regenerateQr).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void detailsAreValidated() {
    assertThatThrownBy(() -> details("PLUMBING", LocalDate.of(2026, 1, 1), null))
        .hasMessageContaining("category must be one of");
    assertThatThrownBy(() -> details("ELECTRICAL", LocalDate.of(2026, 1, 1), LocalDate.of(2025, 1, 1)))
        .hasMessageContaining("warrantyUntil");
  }

  @Test
  void coverageOnlyMovesForward() {
    Asset a = asset();
    a.coverWarrantyUntil(LocalDate.of(2027, 1, 1));
    a.coverWarrantyUntil(LocalDate.of(2026, 6, 1));
    assertThat(a.getWarrantyUntil()).isEqualTo(LocalDate.of(2027, 1, 1));
  }

  @Test
  void photosAreDeduplicated() {
    Asset a = asset();
    var media = UuidV7.next();
    a.addPhoto(media);
    a.addPhoto(media);
    assertThat(a.getPhotoMediaIds()).containsExactly(media);
    assertThat(a.getPhotoMediaId()).isEqualTo(media);
  }

  @Test
  void expiryThresholds() {
    LocalDate today = LocalDate.of(2026, 9, 29);
    assertThat(ExpiryAlert.thresholdReached(today.plusDays(120), today)).isNull();
    assertThat(ExpiryAlert.thresholdReached(today.plusDays(90), today)).isEqualTo(90);
    assertThat(ExpiryAlert.thresholdReached(today.plusDays(55), today)).isEqualTo(60);
    assertThat(ExpiryAlert.thresholdReached(today.plusDays(30), today)).isEqualTo(30);
    assertThat(ExpiryAlert.thresholdReached(today.plusDays(7), today)).isEqualTo(7);
    assertThat(ExpiryAlert.thresholdReached(today, today)).isEqualTo(7);
    assertThat(ExpiryAlert.thresholdReached(today.minusDays(1), today)).isNull();
  }

  @Test
  void amcIsValidated() {
    assertThatThrownBy(() -> new AmcContract.Details(null, null, LocalDate.of(2026, 1, 1), LocalDate.of(2025, 1, 1), 0, 0, null, null))
        .hasMessageContaining("endsOn");
    assertThatThrownBy(() -> new AmcContract.Details(null, null, LocalDate.of(2026, 1, 1), LocalDate.of(2027, 1, 1), 0, 0, "GOLD", null))
        .hasMessageContaining("coverage");
  }

  private static Asset asset() {
    return new Asset("AST-1", details("ELECTRICAL", null, null));
  }

  private static Asset.Details details(String category, LocalDate installed, LocalDate warranty) {
    return new Asset.Details("DG set 125 kVA", category, null, null, "Kirloskar", "KG1", "SN1", null, null, "E1", "A1",
        "125 kVA", null, null, List.of(), null, installed, null, null, warranty, null, null, null);
  }
}
