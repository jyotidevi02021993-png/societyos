package in.societyos.dashboard;

import static org.assertj.core.api.Assertions.assertThat;

import in.societyos.dashboard.platform.core.UuidV7;
import in.societyos.dashboard.platform.test.IntegrationTestBase;
import in.societyos.dashboard.platform.test.TestJwtIssuer;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.RestClient;

/** Keeps the platform wiring honest: security chain, tenant check, RFC 7807 errors. */
class PlatformSmokeIntegrationTest extends IntegrationTestBase {

  @LocalServerPort int port;

  @Test
  void securityChainAndProblemDetails() {
    RestClient http =
        RestClient.builder()
            .baseUrl("http://localhost:" + port)
            .defaultStatusHandler(HttpStatusCode::isError, (req, res) -> {})
            .build();
    UUID user = UuidV7.next();
    UUID society = UuidV7.next();
    String token = TestJwtIssuer.token(user, society, "ESTATE_MANAGER");

    assertThat(http.get().uri("/actuator/health").retrieve().toBodilessEntity().getStatusCode().value()).isEqualTo(200);
    assertThat(http.get().uri("/v1/anything").retrieve().toBodilessEntity().getStatusCode().value()).isEqualTo(401);

    var notFound = http.get().uri("/v1/anything").header("Authorization", "Bearer " + token).retrieve().toEntity(String.class);
    assertThat(notFound.getStatusCode().value()).isEqualTo(404);
    assertThat(notFound.getBody()).contains("\"code\":\"HTTP_404\"").contains("traceId");

    var foreign = http.get().uri("/v1/anything").header("Authorization", "Bearer " + token)
        .header("X-Society-Id", UuidV7.next().toString()).retrieve().toEntity(String.class);
    assertThat(foreign.getStatusCode().value()).isEqualTo(403);
    assertThat(foreign.getBody()).contains("SOCIETY_NOT_ALLOWED");
  }
}
