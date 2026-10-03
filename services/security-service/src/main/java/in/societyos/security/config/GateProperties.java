package in.societyos.security.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Gate defaults. Per-society values ({@code gateApprovalTimeoutSeconds},
 * {@code visitorRetentionDays}) arrive with {@code society.settings.updated} and win over these.
 *
 * @param approvalTimeout how long a walk-in waits for a resident before it expires
 * @param retentionDays visitor photos and gate logs are purged after this many days
 * @param maxPassValidity longest validity window a resident may give a gate pass
 * @param maxPassUses most uses of one gate pass
 */
@ConfigurationProperties(prefix = "sos.gate")
public record GateProperties(
    @DefaultValue("120s") Duration approvalTimeout,
    @DefaultValue("180") int retentionDays,
    @DefaultValue("31d") Duration maxPassValidity,
    @DefaultValue("50") int maxPassUses) {}
