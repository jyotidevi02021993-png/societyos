package in.societyos.security.gatepass.application;

import in.societyos.security.config.GateProperties;
import in.societyos.security.directory.application.DirectoryService;
import in.societyos.security.directory.application.GateAccess;
import in.societyos.security.gatepass.domain.GatePass;
import in.societyos.security.gatepass.domain.GatePass.PassRejectedException;
import in.societyos.security.gatepass.domain.GatePass.Rejection;
import in.societyos.security.gatepass.domain.PassEvents;
import in.societyos.security.gatepass.infrastructure.GatePassRepository;
import in.societyos.security.platform.core.error.ProblemException;
import in.societyos.security.platform.core.tenant.TenantContext;
import in.societyos.security.platform.events.DomainEvents;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Pre-approvals: residents issue passes for their own flat; guards verify and use them at the gate. */
@Service
public class GatePassService {

  /** {@code mine}: the caller lives in the pass flat, so the code and QR may be shown. */
  public record PassView(GatePass pass, String flatLabel, Rejection rejection, boolean mine) {}

  public record Share(String code, String qrToken, String message) {}

  private static final SecureRandom RANDOM = new SecureRandom();

  private final GatePassRepository passes;
  private final DirectoryService directory;
  private final GateAccess access;
  private final DomainEvents events;
  private final GateProperties props;
  private final Clock clock;

  public GatePassService(GatePassRepository passes, DirectoryService directory, GateAccess access,
      DomainEvents events, GateProperties props, Clock clock) {
    this.passes = passes;
    this.directory = directory;
    this.access = access;
    this.events = events;
    this.props = props;
    this.clock = clock;
  }

  @Transactional
  public PassView create(UUID flatId, String kind, String guestName, Instant validFrom, Instant validTo,
      Integer maxUses) {
    access.requireResidentOf(flatId);
    String label = directory.requireFlat(flatId).getLabel();
    Instant now = clock.instant();
    GatePass pass;
    try {
      pass = GatePass.issue(flatId, kind, guestName, validFrom, validTo, maxUses, now,
          new GatePass.Limits(props.maxPassValidity(), props.maxPassUses()), freeCode(), qrToken());
    } catch (IllegalArgumentException e) {
      throw ProblemException.badRequest("INVALID_GATE_PASS", e.getMessage());
    }
    GatePass saved = passes.save(pass);
    events.publish(PassEvents.created(saved));
    return new PassView(saved, label, null, true);
  }

  @Transactional(readOnly = true)
  public List<PassView> list(UUID flatId) {
    Instant now = clock.instant();
    List<GatePass> found;
    if (flatId != null) {
      access.requireFlatView(flatId);
      found = passes.findBySocietyIdAndFlatIdInAndValidToAfterOrderByValidFromDesc(society(), List.of(flatId),
          now.minus(props.maxPassValidity()));
    } else if (access.canSeeSocietyLog()) {
      found = passes.findBySocietyIdAndValidToAfterOrderByValidFromDesc(society(), now, Limit.of(200));
    } else {
      List<UUID> mine = access.myFlatIds();
      found = mine.isEmpty() ? List.of()
          : passes.findBySocietyIdAndFlatIdInAndValidToAfterOrderByValidFromDesc(society(), mine,
              now.minus(props.maxPassValidity()));
    }
    Map<UUID, String> labels = directory.labels(found.stream().map(GatePass::getFlatId).toList());
    List<UUID> myFlats = access.myFlatIds();
    return found.stream().map(p -> new PassView(p, labels.get(p.getFlatId()), p.rejectionAt(now).orElse(null),
        myFlats.contains(p.getFlatId()))).toList();
  }

  @Transactional(readOnly = true)
  public PassView get(UUID id) {
    GatePass p = require(id);
    access.requireFlatView(p.getFlatId());
    return view(p);
  }

  /** The text a resident forwards to the guest (WhatsApp/SMS from their own phone). */
  @Transactional(readOnly = true)
  public Share share(UUID id) {
    GatePass p = require(id);
    access.requireResidentOf(p.getFlatId());
    if (!"ACTIVE".equals(p.getStatus())) {
      throw ProblemException.unprocessable("PASS_NOT_ACTIVE", "Only an active pass can be shared");
    }
    String label = directory.labels(List.of(p.getFlatId())).get(p.getFlatId());
    String message = "Your entry code for %s is %s (valid %s to %s UTC). Show it at the gate."
        .formatted(label == null ? "the society" : label, p.getCode(), p.getValidFrom(), p.getValidTo());
    return new Share(p.getCode(), p.getQrToken(), message);
  }

  @Transactional
  public PassView cancel(UUID id) {
    GatePass p = require(id);
    access.requireResidentOf(p.getFlatId());
    try {
      p.cancel();
    } catch (IllegalStateException e) {
      throw ProblemException.unprocessable("PASS_NOT_ACTIVE", e.getMessage());
    }
    return view(passes.save(p));
  }

  /** Guard check without using the pass. */
  @Transactional(readOnly = true)
  public PassView verify(String code, String qrToken) {
    return view(find(code, qrToken));
  }

  /** Uses one entry of the pass in the caller transaction; rejects with a precise reason. */
  @Transactional(propagation = Propagation.MANDATORY)
  public PassView use(String code, String qrToken) {
    GatePass p = find(code, qrToken);
    try {
      p.use(clock.instant());
    } catch (PassRejectedException e) {
      throw ProblemException.unprocessable("PASS_" + e.rejection().name(), e.getMessage());
    }
    return view(passes.save(p));
  }

  /**
   * Edge agent: the gate already admitted the guest offline. Uses the pass when it still can and
   * reports the reason when it cannot, without failing the caller transaction.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public Optional<GatePass> useOffline(String code, String qrToken, Instant at, List<String> warnings) {
    Optional<GatePass> found = Optional.empty();
    if (qrToken != null && !qrToken.isBlank()) {
      found = passes.findBySocietyIdAndQrToken(society(), qrToken.trim());
    } else if (code != null && code.trim().matches("\\d{6}")) {
      found = passes.findBySocietyIdAndCodeAndStatus(society(), code.trim(), "ACTIVE");
    }
    if (found.isEmpty()) {
      warnings.add("PASS_NOT_FOUND");
      return Optional.empty();
    }
    GatePass p = found.get();
    Optional<Rejection> rejection = p.rejectionAt(at);
    if (rejection.isPresent()) {
      warnings.add("PASS_" + rejection.get().name());
    } else {
      p.use(at);
      passes.save(p);
    }
    return found;
  }

  /** Expiry job: passes past their window leave ACTIVE so their code can be reissued. */
  @Transactional
  public int expireDue(Instant now) {
    List<GatePass> due = passes.findBySocietyIdAndStatusAndValidToLessThanEqual(society(), "ACTIVE", now,
        Limit.of(500));
    due.forEach(p -> p.expireIfPast(now));
    passes.saveAll(due);
    return due.size();
  }

  /** Edge agent: passes changed since {@code since} (valid ones to cache, others to drop). */
  @Transactional(readOnly = true)
  public List<GatePass> changedSince(Instant since, int limit) {
    return passes.findBySocietyIdAndUpdatedAtAfterOrderByUpdatedAtAsc(society(),
        since == null ? Instant.EPOCH : since, Limit.of(limit));
  }

  private GatePass find(String code, String qrToken) {
    Optional<GatePass> found;
    if (qrToken != null && !qrToken.isBlank()) {
      found = passes.findBySocietyIdAndQrToken(society(), qrToken.trim());
    } else if (code != null && code.trim().matches("\\d{6}")) {
      // An active pass owns the code; otherwise the latest pass with it explains why it is refused.
      found = passes.findBySocietyIdAndCodeAndStatus(society(), code.trim(), "ACTIVE")
          .or(() -> passes.findFirstBySocietyIdAndCodeOrderByCreatedAtDesc(society(), code.trim()));
    } else {
      throw ProblemException.badRequest("PASS_CODE_REQUIRED", "Pass a 6-digit code or a QR token");
    }
    return found.orElseThrow(() -> ProblemException.notFound("pass", "for this code"));
  }

  private GatePass require(UUID id) {
    return passes.findById(id).orElseThrow(() -> ProblemException.notFound("pass", id));
  }

  private PassView view(GatePass p) {
    return new PassView(p, directory.labels(List.of(p.getFlatId())).get(p.getFlatId()),
        p.rejectionAt(clock.instant()).orElse(null), access.isResidentOf(p.getFlatId()));
  }

  private String freeCode() {
    for (int i = 0; i < 20; i++) {
      String code = "%06d".formatted(RANDOM.nextInt(1_000_000));
      if (!passes.existsBySocietyIdAndCodeAndStatus(society(), code, "ACTIVE")) {
        return code;
      }
    }
    throw ProblemException.conflict("PASS_CODE_EXHAUSTED", "Could not allocate a pass code, try again");
  }

  private static String qrToken() {
    byte[] b = new byte[24];
    RANDOM.nextBytes(b);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
  }

  private static UUID society() {
    return TenantContext.activeSocietyId();
  }
}
