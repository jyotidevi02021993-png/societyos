package in.societyos.vendor.vendor.application;

import in.societyos.vendor.common.Texts;
import in.societyos.vendor.platform.core.Hashing;
import in.societyos.vendor.platform.core.error.ProblemException;
import in.societyos.vendor.platform.core.tenant.TenantContext;
import in.societyos.vendor.platform.events.DomainEvents;
import in.societyos.vendor.platform.jpa.DocumentNumberService;
import in.societyos.vendor.platform.security.FieldCrypto;
import in.societyos.vendor.vendor.domain.Agent;
import in.societyos.vendor.vendor.domain.Vendor;
import in.societyos.vendor.vendor.domain.VendorEvents;
import in.societyos.vendor.vendor.domain.VendorKycDocument;
import in.societyos.vendor.vendor.domain.VendorRating;
import in.societyos.vendor.vendor.infrastructure.AgentRepository;
import in.societyos.vendor.vendor.infrastructure.VendorKycDocumentRepository;
import in.societyos.vendor.vendor.infrastructure.VendorRatingRepository;
import in.societyos.vendor.vendor.infrastructure.VendorRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Vendor master: registration, KYC documents, status, ratings and agents. */
@Service
public class VendorService {

  public record VendorCommand(String code, String name, String category, List<String> workScopes, String gstin,
      String pan, String contactName, String contactPhone, String contactEmail, String address, String agreementRef,
      LocalDate agreementValidUntil, String riskLevel) {}

  public record AgentCommand(UUID vendorId, UUID userId, String code, String name, String role, List<String> skills) {}

  /** A vendor with its contact details decrypted and masked for display. */
  public record VendorView(Vendor vendor, String panMasked, String contactPhoneMasked, String contactEmail,
      List<VendorKycDocument> kyc) {}

  private final VendorRepository vendors;
  private final VendorKycDocumentRepository kyc;
  private final VendorRatingRepository ratings;
  private final AgentRepository agents;
  private final DomainEvents events;
  private final FieldCrypto crypto;
  private final Clock clock;
  private final DocumentNumberService numbers;

  public VendorService(VendorRepository vendors, VendorKycDocumentRepository kyc, VendorRatingRepository ratings,
      AgentRepository agents, DomainEvents events, FieldCrypto crypto, Clock clock, DocumentNumberService numbers) {
    this.numbers = numbers;
    this.vendors = vendors;
    this.kyc = kyc;
    this.ratings = ratings;
    this.agents = agents;
    this.events = events;
    this.crypto = crypto;
    this.clock = clock;
  }

  @Transactional
  public VendorView register(VendorCommand c) {
    String code = Texts.code(c.code());
    if (code == null) {
      code = numbers.next("VEN");
    } else if (vendors.existsByCode(code)) {
      throw ProblemException.conflict("VENDOR_CODE_EXISTS", "Vendor code " + code + " is taken");
    }
    Vendor v = Vendor.register(code, Texts.clean(c.name()), Texts.upper(c.category()));
    apply(v, c);
    vendors.save(v);
    events.publish(VendorEvents.created(v));
    return view(v);
  }

  @Transactional
  public VendorView update(UUID id, VendorCommand c) {
    Vendor v = require(id);
    apply(v, c);
    vendors.save(v);
    events.publish(VendorEvents.updated(v));
    return view(v);
  }

  @Transactional
  public VendorView changeStatus(UUID id, String status) {
    Vendor v = require(id);
    v.changeStatus(Texts.upper(status));
    vendors.save(v);
    events.publish(VendorEvents.updated(v));
    return view(v);
  }

  @Transactional
  public VendorKycDocument attachKyc(UUID vendorId, String kind, UUID mediaId, LocalDate validUntil) {
    require(vendorId);
    if (mediaId == null) {
      throw ProblemException.badRequest("MEDIA_REQUIRED", "mediaId is required");
    }
    return kyc.save(VendorKycDocument.attach(vendorId, Texts.upper(kind), mediaId, validUntil));
  }

  @Transactional
  public VendorKycDocument verifyKyc(UUID vendorId, UUID documentId) {
    VendorKycDocument d = kyc.findById(documentId).filter(k -> k.getVendorId().equals(vendorId))
        .orElseThrow(() -> ProblemException.notFound("kyc_document", documentId));
    d.verify(TenantContext.userId().orElse(null), clock.instant());
    return kyc.save(d);
  }

  @Transactional
  public VendorView rate(UUID vendorId, int score, UUID poId, String comment) {
    Vendor v = require(vendorId);
    v.rate(score);
    ratings.save(new VendorRating(vendorId, poId, score, Texts.clean(comment)));
    vendors.save(v);
    return view(v);
  }

  @Transactional(readOnly = true)
  public List<Vendor> list(String category) {
    String c = Texts.upper(category);
    return c == null ? vendors.findAllByOrderByNameAsc() : vendors.findByCategoryOrderByNameAsc(c);
  }

  @Transactional(readOnly = true)
  public VendorView get(UUID id) {
    return view(require(id));
  }

  /** For other features: the vendor, or 404 (RLS hides other societies' vendors). */
  @Transactional(readOnly = true)
  public Vendor require(UUID id) {
    return vendors.findById(id).orElseThrow(() -> ProblemException.notFound("vendor", id));
  }

  // --- agents -----------------------------------------------------------------------------

  @Transactional
  public Agent createAgent(AgentCommand c) {
    if (c.vendorId() != null) {
      require(c.vendorId());
    }
    String code = Texts.code(c.code());
    if (code != null && agents.existsByCode(code)) {
      throw ProblemException.conflict("AGENT_CODE_EXISTS", "Agent code " + code + " is taken");
    }
    if (c.userId() != null && agents.findByUserId(c.userId()).isPresent()) {
      throw ProblemException.conflict("AGENT_USER_EXISTS", "This user is already an agent");
    }
    Agent a = agents.save(Agent.create(c.vendorId(), c.userId(), code, Texts.clean(c.name()), Texts.upper(c.role()),
        Texts.upperAll(c.skills())));
    events.publish(VendorEvents.agentCreated(a));
    return a;
  }

  @Transactional(readOnly = true)
  public List<Agent> agents(UUID vendorId) {
    return vendorId == null ? agents.findAllByOrderByNameAsc() : agents.findByVendorIdOrderByNameAsc(vendorId);
  }

  @Transactional
  public Agent deactivateAgent(UUID id) {
    Agent a = agents.findById(id).orElseThrow(() -> ProblemException.notFound("agent", id));
    a.deactivate();
    return agents.save(a);
  }

  /**
   * The vendor the calling user works for (vendor portal). Only an active agent of an active,
   * non-blacklisted vendor gets through; everyone else is refused.
   */
  @Transactional(readOnly = true)
  public Vendor portalVendor() {
    UUID user = TenantContext.userId().orElseThrow(() -> ProblemException.forbidden("NOT_A_VENDOR", "No user"));
    Agent a = agents.findByUserId(user).filter(Agent::isActive).filter(x -> x.getVendorId() != null)
        .orElseThrow(() -> ProblemException.forbidden("NOT_A_VENDOR", "You are not linked to a vendor"));
    Vendor v = require(a.getVendorId());
    if ("BLACKLISTED".equals(v.getStatus())) {
      throw ProblemException.forbidden("VENDOR_BLACKLISTED", "This vendor is blacklisted");
    }
    return v;
  }

  // --- helpers ----------------------------------------------------------------------------

  private void apply(Vendor v, VendorCommand c) {
    v.describe(Texts.clean(c.name()), Texts.upper(c.category()), Texts.upperAll(c.workScopes()),
        Texts.code(c.gstin()), Texts.clean(c.contactName()), Texts.clean(c.address()), Texts.clean(c.agreementRef()),
        c.agreementValidUntil(), Texts.upper(c.riskLevel()));
    String pan = Texts.code(c.pan());
    if (pan != null && !pan.matches("[A-Z]{5}[0-9]{4}[A-Z]")) {
      throw ProblemException.badRequest("INVALID_PAN", "PAN must look like ABCDE1234F");
    }
    String phone = phone(c.contactPhone());
    v.protectedContact(encrypt(pan), encrypt(phone), encrypt(Texts.clean(c.contactEmail())));
  }

  private String encrypt(String plain) {
    return plain == null ? null : crypto.encrypt(plain);
  }

  private String decrypt(String stored) {
    return stored == null ? null : crypto.decrypt(stored);
  }

  static String phone(String raw) {
    String t = Texts.clean(raw);
    if (t == null) {
      return null;
    }
    String digits = t.replaceAll("[^0-9]", "");
    if (digits.length() == 12 && digits.startsWith("91")) {
      digits = digits.substring(2);
    }
    if (digits.length() != 10) {
      throw ProblemException.badRequest("INVALID_PHONE", "Phone must have 10 digits");
    }
    return "+91" + digits;
  }

  private VendorView view(Vendor v) {
    String pan = decrypt(v.getPanEnc());
    String phone = decrypt(v.getContactPhoneEnc());
    String panMasked = pan == null ? null : "XXXXXX" + pan.substring(pan.length() - 4);
    return new VendorView(v, panMasked, phone == null ? null : Hashing.maskPhone(phone), decrypt(v.getContactEmailEnc()),
        kyc.findByVendorIdOrderByCreatedAtAsc(v.getId()));
  }
}
