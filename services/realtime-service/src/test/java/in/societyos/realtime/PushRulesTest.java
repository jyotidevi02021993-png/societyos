package in.societyos.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.societyos.realtime.push.domain.Delivery;
import in.societyos.realtime.push.domain.PushRouter;
import in.societyos.realtime.socket.domain.DestinationPolicy;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PushRulesTest {

  static final UUID SOCIETY = UUID.randomUUID();
  static final UUID R1 = UUID.randomUUID();
  static final UUID R2 = UUID.randomUUID();

  @Test
  void entryRequestGoesToEachResidentOnceAndTheGuardConsole() {
    List<Delivery> d = PushRouter.route("security.entry.requested", SOCIETY, List.of(R1, R2, R1));
    assertThat(d).containsExactly(Delivery.user(R1, "gate"), Delivery.user(R2, "gate"),
        Delivery.society(SOCIETY, "gate"));
    assertThat(d.get(2).destination()).isEqualTo("/topic/society." + SOCIETY + ".gate");
    assertThat(d.get(0).destination()).isEqualTo("/queue/gate");
  }

  @Test
  void followUpsReachTheSameAudienceAndAlertsGoToDashboards() {
    assertThat(PushRouter.route("security.entry.approved", SOCIETY, List.of(R1)))
        .containsExactly(Delivery.user(R1, "gate"), Delivery.society(SOCIETY, "gate"));
    assertThat(PushRouter.route("security.entry.expired", SOCIETY, List.of()))
        .containsExactly(Delivery.society(SOCIETY, "gate"));
    assertThat(PushRouter.route("security.sos.raised", SOCIETY, List.of()))
        .containsExactly(Delivery.society(SOCIETY, "gate"), Delivery.society(SOCIETY, "alerts"));
    assertThat(PushRouter.route("security.incident.reported", SOCIETY, List.of()))
        .containsExactly(Delivery.society(SOCIETY, "alerts"));
    assertThat(PushRouter.route("security.pass.created", SOCIETY, List.of(R1))).isEmpty();
    assertThat(PushRouter.route("security.entry.requested", null, List.of(R1))).isEmpty();
  }

  @Test
  void redisChannelsRoundTrip() {
    for (Delivery d : List.of(Delivery.user(R1, "gate"), Delivery.society(SOCIETY, "alerts"))) {
      assertThat(Delivery.fromRedisChannel(d.redisChannel())).isEqualTo(d);
    }
    assertThat(Delivery.user(R1, "gate").redisChannel()).isEqualTo("rt:user:" + R1 + ":gate");
    assertThatThrownBy(() -> Delivery.fromRedisChannel("presence:" + R1)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void subscriptionsNeedASocietyAndAPermissionExceptPrivateQueues() {
    assertThat(DestinationPolicy.requirementFor("/user/queue/gate")).get()
        .satisfies(r -> assertThat(r.isPrivate()).isTrue());
    var gate = DestinationPolicy.requirementFor("/topic/society." + SOCIETY + ".gate").orElseThrow();
    assertThat(gate.societyId()).isEqualTo(SOCIETY);
    assertThat(gate.anyPermission()).contains("gate:entry");
    var alerts = DestinationPolicy.requirementFor("/topic/society." + SOCIETY + ".alerts").orElseThrow();
    assertThat(alerts.anyPermission()).contains("incident:manage");
    assertThat(DestinationPolicy.requirementFor("/topic/society." + SOCIETY + ".billing")).isEmpty();
    assertThat(DestinationPolicy.requirementFor("/topic/anything")).isEmpty();
    assertThat(DestinationPolicy.requirementFor("/user/" + R1 + "/queue/gate")).isEmpty();
    assertThat(DestinationPolicy.requirementFor(null)).isEmpty();
  }
}
