package in.societyos.billing.tariff.application;

import in.societyos.billing.platform.core.error.ProblemException;
import in.societyos.billing.tariff.domain.ChargeHead;
import in.societyos.billing.tariff.domain.TariffCalculator;
import in.societyos.billing.tariff.domain.TariffCalculator.Basis;
import in.societyos.billing.tariff.infrastructure.ChargeHeadRepository;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** Charge heads (tariffs). Changing a head never touches published bills: their lines are snapshots. */
@Service
public class ChargeHeadService {

  public record HeadInput(String name, String basis, long ratePaise, Map<String, Long> flatTypeRates,
      boolean gstApplicable, boolean appliesToVacant, boolean active, int sortOrder) {}

  private static final TypeReference<Map<String, Long>> RATES = new TypeReference<>() {};

  private final ChargeHeadRepository heads;
  private final JsonMapper json;

  public ChargeHeadService(ChargeHeadRepository heads, JsonMapper json) {
    this.heads = heads;
    this.json = json;
  }

  @Transactional(readOnly = true)
  public List<ChargeHead> list() {
    return heads.findAllByOrderBySortOrderAscCodeAsc();
  }

  @Transactional(readOnly = true)
  public ChargeHead require(UUID id) {
    return heads.findById(id).orElseThrow(() -> ProblemException.notFound("charge_head", id));
  }

  @Transactional
  public ChargeHead create(String code, HeadInput in) {
    String clean = code == null ? "" : code.trim().toUpperCase();
    if (!clean.matches("[A-Z0-9_-]{1,30}")) {
      throw ProblemException.badRequest("INVALID_CODE", "code is 1-30 letters, digits, _ or -");
    }
    if (heads.existsByCodeIgnoreCase(clean)) {
      throw ProblemException.conflict("CHARGE_HEAD_EXISTS", "A charge head with this code exists");
    }
    ChargeHead head = new ChargeHead(clean);
    apply(head, in);
    return heads.save(head);
  }

  @Transactional
  public ChargeHead update(UUID id, HeadInput in) {
    ChargeHead head = require(id);
    apply(head, in);
    return heads.save(head);
  }

  /** Active heads as calculator tariffs, in bill-line order. */
  @Transactional(readOnly = true)
  public List<TariffCalculator.Tariff> activeTariffs() {
    return heads.findByActiveTrueOrderBySortOrderAscCodeAsc().stream()
        .map(h -> new TariffCalculator.Tariff(h.getCode(), h.getName(), h.getBasis(), h.getRatePaise(),
            rates(h), h.isGstApplicable(), h.isAppliesToVacant()))
        .toList();
  }

  public Map<String, Long> rates(ChargeHead head) {
    return json.readValue(head.getFlatTypeRatesJson(), RATES);
  }

  private void apply(ChargeHead head, HeadInput in) {
    Basis basis;
    try {
      basis = Basis.valueOf(in.basis() == null ? "" : in.basis().trim().toUpperCase());
    } catch (IllegalArgumentException e) {
      throw ProblemException.badRequest("INVALID_BASIS", "basis must be FIXED, PER_SQFT or FLAT_TYPE");
    }
    if (in.ratePaise() < 0) {
      throw ProblemException.badRequest("INVALID_RATE", "ratePaise must not be negative");
    }
    Map<String, Long> rates = new LinkedHashMap<>();
    if (in.flatTypeRates() != null) {
      in.flatTypeRates().forEach((type, rate) -> {
        if (type == null || type.isBlank() || rate == null || rate < 0) {
          throw ProblemException.badRequest("INVALID_RATE", "flatTypeRates needs a type and a rate >= 0");
        }
        rates.put(type.trim().toUpperCase(), rate);
      });
    }
    if (basis == Basis.FLAT_TYPE && rates.isEmpty() && in.ratePaise() == 0) {
      throw ProblemException.badRequest("INVALID_RATE", "A FLAT_TYPE head needs flatTypeRates or a fallback rate");
    }
    if (basis != Basis.FLAT_TYPE && in.ratePaise() == 0) {
      throw ProblemException.badRequest("INVALID_RATE", "ratePaise must be positive");
    }
    head.update(in.name().trim(), basis, in.ratePaise(), json.writeValueAsString(rates), in.gstApplicable(),
        in.appliesToVacant(), in.active(), in.sortOrder());
  }
}
