package in.societyos.audit.log.infrastructure;

import in.societyos.audit.log.application.AuditRecorder;
import in.societyos.audit.platform.events.CloudEvent;
import in.societyos.audit.platform.events.DomainEventListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Subscribes to every service topic {@code sos.<ctx>.events.v1} (all types) in one consumer group,
 * {@value #GROUP}; failures go to {@code sos.dlq.audit.log} after three retries. The platform inbox
 * makes redelivery harmless. A new context needs one more method here.
 */
@Component
public class AuditEventListeners {

  public static final String GROUP = "audit.log";

  private final AuditRecorder recorder;

  public AuditEventListeners(AuditRecorder recorder) {
    this.recorder = recorder;
  }

  @DomainEventListener(topic = "sos.identity.events.v1", group = GROUP)
  public void onIdentity(CloudEvent<JsonNode> e) {
    recorder.record(e);
  }

  @DomainEventListener(topic = "sos.society.events.v1", group = GROUP)
  public void onSociety(CloudEvent<JsonNode> e) {
    recorder.record(e);
  }

  @DomainEventListener(topic = "sos.security.events.v1", group = GROUP)
  public void onSecurity(CloudEvent<JsonNode> e) {
    recorder.record(e);
  }

  @DomainEventListener(topic = "sos.billing.events.v1", group = GROUP)
  public void onBilling(CloudEvent<JsonNode> e) {
    recorder.record(e);
  }

  @DomainEventListener(topic = "sos.asset.events.v1", group = GROUP)
  public void onAsset(CloudEvent<JsonNode> e) {
    recorder.record(e);
  }

  @DomainEventListener(topic = "sos.ticket.events.v1", group = GROUP)
  public void onTicket(CloudEvent<JsonNode> e) {
    recorder.record(e);
  }

  @DomainEventListener(topic = "sos.workflow.events.v1", group = GROUP)
  public void onWorkflow(CloudEvent<JsonNode> e) {
    recorder.record(e);
  }

  @DomainEventListener(topic = "sos.community.events.v1", group = GROUP)
  public void onCommunity(CloudEvent<JsonNode> e) {
    recorder.record(e);
  }

  @DomainEventListener(topic = "sos.notification.events.v1", group = GROUP)
  public void onNotification(CloudEvent<JsonNode> e) {
    recorder.record(e);
  }

  @DomainEventListener(topic = "sos.media.events.v1", group = GROUP)
  public void onMedia(CloudEvent<JsonNode> e) {
    recorder.record(e);
  }

  @DomainEventListener(topic = "sos.utility.events.v1", group = GROUP)
  public void onUtility(CloudEvent<JsonNode> e) {
    recorder.record(e);
  }

  @DomainEventListener(topic = "sos.vendor.events.v1", group = GROUP)
  public void onVendor(CloudEvent<JsonNode> e) {
    recorder.record(e);
  }

  @DomainEventListener(topic = "sos.inventory.events.v1", group = GROUP)
  public void onInventory(CloudEvent<JsonNode> e) {
    recorder.record(e);
  }

  @DomainEventListener(topic = "sos.compliance.events.v1", group = GROUP)
  public void onCompliance(CloudEvent<JsonNode> e) {
    recorder.record(e);
  }

  @DomainEventListener(topic = "sos.ai.events.v1", group = GROUP)
  public void onAi(CloudEvent<JsonNode> e) {
    recorder.record(e);
  }

  @DomainEventListener(topic = "sos.marketplace.events.v1", group = GROUP)
  public void onMarketplace(CloudEvent<JsonNode> e) {
    recorder.record(e);
  }
}
