package in.societyos.security.visitor.application;

import in.societyos.security.common.Phones;
import in.societyos.security.platform.core.Hashing;
import in.societyos.security.platform.core.error.ProblemException;
import in.societyos.security.platform.core.tenant.TenantContext;
import in.societyos.security.platform.security.FieldCrypto;
import in.societyos.security.visitor.domain.Visitor;
import in.societyos.security.visitor.infrastructure.VisitorRepository;
import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Visitors: encrypted phone + keyed hash; callers only ever get the masked form. */
@Service
public class VisitorService {

  public record VisitorView(UUID id, String name, String phoneMasked, UUID photoMediaId, Instant lastSeenAt) {}

  private final VisitorRepository visitors;
  private final FieldCrypto crypto;

  public VisitorService(VisitorRepository visitors, FieldCrypto crypto) {
    this.visitors = visitors;
    this.crypto = crypto;
  }

  /** Finds the returning visitor by phone, or records a new one. Runs in the caller transaction. */
  @Transactional(propagation = Propagation.MANDATORY)
  public Visitor record(String name, String phone, UUID photoMediaId, Instant at) {
    String normalised = Phones.normalise(phone);
    try {
      if (normalised != null) {
        String hash = crypto.hash(normalised);
        Optional<Visitor> known = visitors.findFirstBySocietyIdAndPhoneHashOrderByLastSeenAtDesc(society(), hash);
        if (known.isPresent()) {
          known.get().seenAgain(name, photoMediaId, at);
          return visitors.save(known.get());
        }
        return visitors.save(new Visitor(name, crypto.encrypt(normalised), hash, photoMediaId, at));
      }
      return visitors.save(new Visitor(name, null, null, photoMediaId, at));
    } catch (IllegalArgumentException e) {
      throw ProblemException.badRequest("INVALID_VISITOR", e.getMessage());
    }
  }

  /** Guard types a phone: pre-fill the name and photo of a returning visitor. */
  @Transactional(readOnly = true)
  public Optional<VisitorView> lookup(String phone) {
    String normalised = Phones.normalise(phone);
    if (normalised == null) {
      throw ProblemException.badRequest("PHONE_REQUIRED", "phone is required");
    }
    return visitors.findFirstBySocietyIdAndPhoneHashOrderByLastSeenAtDesc(society(), crypto.hash(normalised))
        .map(this::view);
  }

  @Transactional(readOnly = true)
  public Map<UUID, VisitorView> views(Collection<UUID> ids) {
    return visitors.findAllById(ids.stream().filter(Objects::nonNull).distinct().toList()).stream()
        .map(this::view).collect(Collectors.toMap(VisitorView::id, Function.identity()));
  }

  public VisitorView view(Visitor v) {
    String masked = v.getPhoneEnc() == null ? null : Hashing.maskPhone(crypto.decrypt(v.getPhoneEnc()));
    return new VisitorView(v.getId(), v.getName(), masked, v.getPhotoMediaId(), v.getLastSeenAt());
  }

  private static UUID society() {
    return TenantContext.activeSocietyId();
  }
}
