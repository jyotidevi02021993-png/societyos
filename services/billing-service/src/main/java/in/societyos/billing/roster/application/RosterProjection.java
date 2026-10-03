package in.societyos.billing.roster.application;

import in.societyos.billing.platform.core.tenant.TenantContext;
import in.societyos.billing.roster.application.SocietyEventData.Flat;
import in.societyos.billing.roster.application.SocietyEventData.Membership;
import in.societyos.billing.roster.application.SocietyEventData.SettingsUpdated;
import in.societyos.billing.roster.domain.BillingSettings;
import in.societyos.billing.roster.domain.FlatMember;
import in.societyos.billing.roster.domain.FlatRef;
import in.societyos.billing.roster.infrastructure.BillingSettingsRepository;
import in.societyos.billing.roster.infrastructure.FlatMemberRepository;
import in.societyos.billing.roster.infrastructure.FlatRefRepository;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Keeps the local copies of society data billing needs: the flat roster (area, type, status),
 * memberships (who may see and pay a flat's bills) and the billing-relevant settings. Runs in the
 * listener transaction with the event society bound; every handler is an upsert, so replays are
 * harmless.
 */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class RosterProjection {

  private final FlatRefRepository flats;
  private final FlatMemberRepository members;
  private final BillingSettingsRepository settings;

  public RosterProjection(FlatRefRepository flats, FlatMemberRepository members, BillingSettingsRepository settings) {
    this.flats = flats;
    this.members = members;
    this.settings = settings;
  }

  public void societyCreated() {
    UUID societyId = TenantContext.activeSocietyId();
    if (!settings.existsById(societyId)) {
      settings.save(new BillingSettings(societyId));
    }
  }

  public void settingsUpdated(SettingsUpdated e) {
    UUID societyId = TenantContext.activeSocietyId();
    BillingSettings s = settings.findById(societyId).orElseGet(() -> new BillingSettings(societyId));
    s.applySociety(e.intSetting("billingDueDay"), e.intSetting("lateFeeGraceDays"));
    settings.save(s);
  }

  public void flat(Flat e) {
    FlatRef f = flats.findById(e.flatId()).orElseGet(() -> new FlatRef(e.flatId()));
    f.apply(e.towerId(), e.towerName(), e.number(), e.label(), e.floor(), e.areaSqft(), e.flatType(), e.status());
    flats.save(f);
  }

  public void membershipCreated(Membership e) {
    FlatMember m = members.findById(e.membershipId()).orElseGet(() -> new FlatMember(e.membershipId()));
    m.apply(e.flatId(), e.userId(), e.kind(), Boolean.TRUE.equals(e.isPrimary()));
    members.save(m);
  }

  public void membershipEnded(Membership e, Instant at) {
    FlatMember m = members.findById(e.membershipId()).orElseGet(() -> {
      FlatMember created = new FlatMember(e.membershipId());
      created.apply(e.flatId(), e.userId(), e.kind(), Boolean.TRUE.equals(e.isPrimary()));
      return created;
    });
    m.end(at);
    members.save(m);
  }
}
