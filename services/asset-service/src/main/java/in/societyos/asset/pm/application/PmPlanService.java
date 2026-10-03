package in.societyos.asset.pm.application;

import in.societyos.asset.asset.domain.Asset;
import in.societyos.asset.asset.infrastructure.AssetRepository;
import in.societyos.asset.platform.core.error.ProblemException;
import in.societyos.asset.pm.domain.Checklist;
import in.societyos.asset.pm.domain.PmFrequency;
import in.societyos.asset.pm.domain.PmPlan;
import in.societyos.asset.pm.infrastructure.PmPlanRepository;
import in.societyos.asset.reference.application.ReferenceData;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

@Service
public class PmPlanService {

  private static final TypeReference<List<Checklist.Item>> ITEMS = new TypeReference<>() {};

  /** What a caller supplies to create or change a plan. */
  public record PlanInput(String name, String frequency, LocalDate anchorOn, Integer leadDays, String usageMetric,
      BigDecimal usageInterval, List<Checklist.Item> checklist, UUID checklistTemplateId, UUID assigneeUserId,
      UUID vendorId) {}

  /** A plan with its parsed checklist. */
  public record PlanView(PmPlan plan, List<Checklist.Item> checklist) {}

  private final PmPlanRepository plans;
  private final AssetRepository assets;
  private final ReferenceData reference;
  private final JsonMapper json;

  public PmPlanService(PmPlanRepository plans, AssetRepository assets, ReferenceData reference, JsonMapper json) {
    this.plans = plans;
    this.assets = assets;
    this.reference = reference;
    this.json = json;
  }

  @Transactional(readOnly = true)
  public List<PlanView> list(UUID assetId) {
    List<PmPlan> found = assetId == null ? plans.findAllByOrderByNextDueOnAscNameAsc()
        : plans.findByAssetIdOrderByNameAsc(assetId);
    return found.stream().map(this::view).toList();
  }

  @Transactional(readOnly = true)
  public PlanView get(UUID id) {
    return view(require(id));
  }

  @Transactional
  public PlanView create(UUID assetId, PlanInput in) {
    Asset asset = assets.findById(assetId).orElseThrow(() -> ProblemException.notFound("asset", assetId));
    if (asset.status() == Asset.Status.DISPOSED) {
      throw ProblemException.unprocessable("ASSET_DISPOSED", "A disposed asset cannot get a PM plan");
    }
    PmPlan plan = plans.save(new PmPlan(assetId, details(in), reference.today()));
    return view(plan);
  }

  /** The plan created with an asset that was registered with a {@code pmFrequency}. */
  @Transactional
  public PmPlan createDefault(Asset asset, PmFrequency frequency, LocalDate anchor) {
    PmPlan.Details d = new PmPlan.Details(asset.getName() + " " + label(frequency) + " PM", frequency, anchor, 0,
        null, null, "[]", null, null, asset.getVendorId());
    return plans.save(new PmPlan(asset.getId(), d, reference.today()));
  }

  @Transactional
  public PlanView update(UUID id, PlanInput in) {
    PmPlan plan = require(id);
    plan.update(details(in), reference.today());
    return view(plans.save(plan));
  }

  @Transactional
  public PlanView setActive(UUID id, boolean active) {
    PmPlan plan = require(id);
    if (active) {
      plan.activate(reference.today());
    } else {
      plan.deactivate();
    }
    return view(plans.save(plan));
  }

  public List<Checklist.Item> checklist(PmPlan plan) {
    List<Checklist.Item> items = json.readValue(plan.getChecklistJson(), ITEMS);
    return items == null ? List.of() : items;
  }

  PmPlan require(UUID id) {
    return plans.findById(id).orElseThrow(() -> ProblemException.notFound("pm_plan", id));
  }

  private PlanView view(PmPlan p) {
    return new PlanView(p, checklist(p));
  }

  private PmPlan.Details details(PlanInput in) {
    try {
      PmFrequency f = PmFrequency.parse(in.frequency() == null ? "" : in.frequency());
      List<Checklist.Item> items = Checklist.validateItems(in.checklist());
      return new PmPlan.Details(in.name(), f, in.anchorOn(), in.leadDays() == null ? 0 : in.leadDays(),
          in.usageMetric(), in.usageInterval(), json.writeValueAsString(items), in.checklistTemplateId(),
          in.assigneeUserId(), in.vendorId());
    } catch (IllegalArgumentException e) {
      throw ProblemException.badRequest("INVALID_PM_PLAN", e.getMessage());
    }
  }

  private static String label(PmFrequency f) {
    String s = f.name().replace('_', '-').toLowerCase(java.util.Locale.ROOT);
    return Character.toUpperCase(s.charAt(0)) + s.substring(1);
  }
}
