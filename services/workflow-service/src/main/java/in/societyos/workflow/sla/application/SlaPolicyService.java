package in.societyos.workflow.sla.application;

import in.societyos.workflow.platform.core.error.ProblemException;
import in.societyos.workflow.sla.domain.EscalationStep;
import in.societyos.workflow.sla.domain.SlaPolicy;
import in.societyos.workflow.sla.infrastructure.SlaPolicyRepository;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

@Service
public class SlaPolicyService {

  public record PolicyCommand(String subjectType, String category, String priority, Integer respondMins,
      Integer resolveMins, Integer warnPercent, List<EscalationStep> escalationChain, Boolean active) {}

  private static final Set<String> PRIORITIES = Set.of("P1", "P2", "P3", "P4");
  private static final TypeReference<List<EscalationStep>> CHAIN = new TypeReference<>() {};

  private final SlaPolicyRepository policies;
  private final JsonMapper json;

  public SlaPolicyService(SlaPolicyRepository policies, JsonMapper json) {
    this.policies = policies;
    this.json = json;
  }

  @Transactional(readOnly = true)
  public List<SlaPolicy> list() {
    return policies.findAllByOrderBySubjectTypeAscPriorityAsc();
  }

  @Transactional
  public SlaPolicy create(PolicyCommand c) {
    return save(new SlaPolicy(subjectType(c.subjectType())), c);
  }

  @Transactional
  public SlaPolicy update(UUID id, PolicyCommand c) {
    return save(policies.findById(id).orElseThrow(() -> ProblemException.notFound("sla_policy", id)), c);
  }

  public List<EscalationStep> chain(SlaPolicy p) {
    return json.readValue(p.getEscalationChainJson(), CHAIN);
  }

  private SlaPolicy save(SlaPolicy p, PolicyCommand c) {
    String priority = c.priority() == null || c.priority().isBlank() ? null : c.priority().trim().toUpperCase(Locale.ROOT);
    if (priority != null && !PRIORITIES.contains(priority)) {
      throw ProblemException.badRequest("INVALID_PRIORITY", "priority must be one of " + PRIORITIES);
    }
    if (c.resolveMins() == null) {
      throw ProblemException.badRequest("RESOLVE_MINS_REQUIRED", "resolveMins is required");
    }
    List<EscalationStep> chain = c.escalationChain() == null ? List.of() : c.escalationChain().stream()
        .map(s -> new EscalationStep(s.afterMins(), s.toRole().trim().toUpperCase(Locale.ROOT))).toList();
    for (int i = 1; i < chain.size(); i++) {
      if (chain.get(i).afterMins() < chain.get(i - 1).afterMins()) {
        throw ProblemException.badRequest("INVALID_CHAIN", "Escalation steps must be in increasing afterMins");
      }
    }
    try {
      p.update(subjectType(c.subjectType()), c.category() == null || c.category().isBlank() ? null : c.category().trim(),
          priority, c.respondMins(), c.resolveMins(), c.warnPercent() == null ? 80 : c.warnPercent(),
          json.writeValueAsString(chain), c.active() == null || c.active());
      return policies.saveAndFlush(p);
    } catch (IllegalArgumentException e) {
      throw ProblemException.badRequest("INVALID_POLICY", e.getMessage());
    } catch (DataIntegrityViolationException e) {
      throw ProblemException.conflict("POLICY_EXISTS", "An active policy for this subject, category and priority exists");
    }
  }

  private static String subjectType(String s) {
    if (s == null || s.isBlank()) {
      throw ProblemException.badRequest("SUBJECT_TYPE_REQUIRED", "subjectType is required");
    }
    return s.trim().toUpperCase(Locale.ROOT);
  }
}
