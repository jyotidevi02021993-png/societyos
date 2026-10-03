package in.societyos.community.platform.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.societyos.community.platform.core.tenant.NoTenantException;
import in.societyos.community.platform.core.tenant.Tenant;
import in.societyos.community.platform.core.tenant.TenantContext;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CoreTest {

  @Test
  void uuidV7IsVersion7AndMonotonic() {
    List<UUID> ids = new ArrayList<>();
    for (int i = 0; i < 10_000; i++) {
      ids.add(UuidV7.next());
    }
    assertThat(ids).allMatch(id -> id.version() == 7 && id.variant() == 2);
    List<UUID> sorted = new ArrayList<>(ids);
    sorted.sort(java.util.Comparator.comparing(UUID::toString));
    assertThat(sorted).isEqualTo(ids);
    assertThat(Math.abs(UuidV7.timestampMillis(ids.getFirst()) - System.currentTimeMillis())).isLessThan(5_000);
  }

  @Test
  void moneyIsExactInPaise() {
    Money bill = Money.ofRupees(new BigDecimal("4250.50"));
    assertThat(bill.amountPaise()).isEqualTo(425_050);
    assertThat(bill.times(new BigDecimal("0.18")).amountPaise()).isEqualTo(76_509); // 765.09
    assertThat(bill.plus(Money.ofPaise(50)).format()).isEqualTo("₹4,251.00");
    assertThatThrownBy(() -> Money.ofRupees(new BigDecimal("1.005"))).isInstanceOf(ArithmeticException.class);
  }

  @Test
  void tenantContextRestoresPreviousTenant() {
    UUID a = UuidV7.next();
    UUID b = UuidV7.next();
    assertThat(TenantContext.optional()).isEmpty();
    TenantContext.runAs(a, () -> {
      assertThat(TenantContext.activeSocietyId()).isEqualTo(a);
      TenantContext.runAs(b, () -> assertThat(TenantContext.activeSocietyId()).isEqualTo(b));
      assertThat(TenantContext.activeSocietyId()).isEqualTo(a);
    });
    assertThat(TenantContext.optional()).isEmpty();
    assertThatThrownBy(TenantContext::current).isInstanceOf(NoTenantException.class);
    assertThatThrownBy(() -> Tenant.platform().requireActiveSociety()).isInstanceOf(NoTenantException.class);
  }

  @Test
  void phonesAreMasked() {
    assertThat(Hashing.maskPhone("+919876543221")).isEqualTo("98XXXXXX21");
  }
}
