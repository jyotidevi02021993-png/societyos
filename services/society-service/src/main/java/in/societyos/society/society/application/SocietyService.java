package in.societyos.society.society.application;

import in.societyos.society.platform.core.UuidV7;
import in.societyos.society.platform.core.error.ProblemException;
import in.societyos.society.platform.core.tenant.Tenant;
import in.societyos.society.platform.core.tenant.TenantContext;
import in.societyos.society.platform.events.DomainEvents;
import in.societyos.society.society.domain.Society;
import in.societyos.society.society.domain.SocietyEvents;
import in.societyos.society.society.domain.SocietySettings;
import in.societyos.society.society.infrastructure.SocietyRepository;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/** Society onboarding, profile and settings. */
@Service
public class SocietyService {

  private final SocietyRepository societies;
  private final JsonMapper jsonMapper;
  private final DomainEvents events;
  private final TransactionTemplate tx;

  public SocietyService(
      SocietyRepository societies, JsonMapper jsonMapper, DomainEvents events, PlatformTransactionManager txManager) {
    this.societies = societies;
    this.jsonMapper = jsonMapper;
    this.events = events;
    this.tx = new TransactionTemplate(txManager);
  }

  /**
   * Onboards a new society (platform admin). The society row is its own tenant, so the insert
   * and the {@code society.created} event run with the new society active; identity-service
   * provisions the default roles when it sees the event.
   */
  public Society onboard(Society.Profile profile, SocietySettings settings) {
    Society.Profile clean = validated(profile);
    SocietySettings resolved = (settings == null ? SocietySettings.defaults() : settings).validated();
    UUID id = UuidV7.next();
    Tenant caller = TenantContext.current();
    Tenant asNewSociety = new Tenant(
        caller.userId(), caller.actorType(), id, List.of(id), caller.roles(), caller.bearerToken());
    try {
      return TenantContext.callAs(asNewSociety, () -> tx.execute(status -> {
        Society saved = societies.save(new Society(id, clean, jsonMapper.writeValueAsString(resolved)));
        events.publish(new SocietyEvents.SocietyCreated(
            id, saved.getName(), saved.getCity(), saved.getState(), saved.getTimezone()));
        return saved;
      }));
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  /** Societies the caller can read (RLS limits the rows to the token's societies). */
  @Transactional(readOnly = true)
  public List<Society> readable() {
    return societies.findAllByOrderByNameAsc();
  }

  @Transactional(readOnly = true)
  public Society current() {
    UUID id = TenantContext.activeSocietyId();
    return societies.findById(id).orElseThrow(() -> ProblemException.notFound("society", id));
  }

  @Transactional
  public Society updateProfile(Society.Profile profile) {
    Society society = current();
    society.apply(validated(profile));
    return societies.save(society);
  }

  @Transactional(readOnly = true)
  public SocietySettings settings() {
    return parseSettings(current().getSettingsJson());
  }

  @Transactional
  public SocietySettings updateSettings(SocietySettings patch) {
    Society society = current();
    SocietySettings settings = parseSettings(society.getSettingsJson()).merge(patch).validated();
    society.replaceSettings(jsonMapper.writeValueAsString(settings));
    societies.save(society);
    events.publish(new SocietyEvents.SettingsUpdated(society.getId(), settings));
    return settings;
  }

  /** Today in the society's own time zone (membership dates, import defaults). */
  @Transactional(readOnly = true)
  public LocalDate today() {
    return LocalDate.now(ZoneId.of(current().getTimezone()));
  }

  private SocietySettings parseSettings(String value) {
    if (value == null || value.isBlank()) {
      return SocietySettings.defaults();
    }
    SocietySettings parsed = jsonMapper.readValue(value, SocietySettings.class);
    return (parsed == null ? SocietySettings.defaults() : parsed).validated();
  }

  private static Society.Profile validated(Society.Profile p) {
    String timezone = p.timezone() == null || p.timezone().isBlank() ? "Asia/Kolkata" : p.timezone().trim();
    try {
      ZoneId.of(timezone);
    } catch (RuntimeException invalidZone) {
      throw ProblemException.badRequest("INVALID_TIMEZONE", "timezone must be a valid IANA time zone");
    }
    return new Society.Profile(p.name().trim(), clean(p.legalName()), clean(p.address()),
        p.city().trim(), p.state().trim(), clean(p.pin()), timezone);
  }

  private static String clean(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }
}
