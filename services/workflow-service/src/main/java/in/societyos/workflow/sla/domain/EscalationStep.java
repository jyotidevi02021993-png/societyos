package in.societyos.workflow.sla.domain;

/**
 * One rung of an escalation chain: {@code afterMins} after the breach, the ticket is escalated to
 * {@code toRole} (Technician → Facility Manager → Estate Manager → RWA).
 */
public record EscalationStep(int afterMins, String toRole) {

  public EscalationStep {
    if (afterMins < 0) {
      throw new IllegalArgumentException("afterMins cannot be negative");
    }
    if (toRole == null || toRole.isBlank()) {
      throw new IllegalArgumentException("toRole is required");
    }
  }
}
