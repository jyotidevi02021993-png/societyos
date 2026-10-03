package in.societyos.vendor.vendor.domain;

import in.societyos.vendor.common.VendorEvent;
import java.util.List;
import java.util.UUID;

/** Vendor master events, exactly as in contracts/events/CATALOGUE.md (vendor section). */
public final class VendorEvents {

  private VendorEvents() {}

  public record VendorCreated(UUID vendorId, String code, String name, List<String> workScopes, String status)
      implements VendorEvent {
    @Override public String type() { return "vendor.vendor.created"; }
    @Override public UUID aggregateId() { return vendorId; }
  }

  public record VendorUpdated(UUID vendorId, String code, String name, List<String> workScopes, String status)
      implements VendorEvent {
    @Override public String type() { return "vendor.vendor.updated"; }
    @Override public UUID aggregateId() { return vendorId; }
  }

  /** The agent's name is a staff name a manager screen needs; no phone or ID numbers. */
  public record AgentCreated(UUID agentId, UUID userId, UUID vendorId, String role, String name)
      implements VendorEvent {
    @Override public String type() { return "vendor.agent.created"; }
    @Override public UUID aggregateId() { return agentId; }
  }

  public static VendorCreated created(Vendor v) {
    return new VendorCreated(v.getId(), v.getCode(), v.getName(), v.getWorkScopes(), v.getStatus());
  }

  public static VendorUpdated updated(Vendor v) {
    return new VendorUpdated(v.getId(), v.getCode(), v.getName(), v.getWorkScopes(), v.getStatus());
  }

  public static AgentCreated agentCreated(Agent a) {
    return new AgentCreated(a.getId(), a.getUserId(), a.getVendorId(), a.getRole(), a.getName());
  }
}
