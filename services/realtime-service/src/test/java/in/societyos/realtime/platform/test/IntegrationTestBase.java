package in.societyos.realtime.platform.test;

import static org.mockito.Mockito.when;

import in.societyos.realtime.platform.security.IdentityPermissionsClient;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.kafka.KafkaContainer;

/**
 * Base for integration tests: one Kafka and Redis per JVM (singleton containers). realtime-service
 * has no database. Tagged {@code integration}: run with {@code ./mvnw verify -Pintegration}.
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
public abstract class IntegrationTestBase {

  protected static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:3.8.1");

  @SuppressWarnings("resource")
  protected static final GenericContainer<?> REDIS =
      new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

  static {
    KAFKA.start();
    REDIS.start();
  }

  @DynamicPropertySource
  static void containerProperties(DynamicPropertyRegistry r) {
    r.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    r.add("spring.data.redis.host", REDIS::getHost);
    r.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    r.add("eureka.client.enabled", () -> "false");
    r.add("spring.cloud.config.enabled", () -> "false");
    r.add("spring.cloud.discovery.enabled", () -> "false");
    r.add("sos.security.jwks-uri", TestJwtIssuer::jwksUri);
  }

  /**
   * identity-service is not running in tests: stub the permissions the caller holds, e.g.
   * {@code givenPermissions("flat:manage")}. Cached permissions are cleared between tests.
   */
  @MockitoBean protected IdentityPermissionsClient identityPermissions;

  @Autowired private StringRedisTemplate redisForTests;

  @BeforeEach
  void clearPermissionCache() {
    var keys = redisForTests.keys("perm:*");
    if (keys != null && !keys.isEmpty()) {
      redisForTests.delete(keys);
    }
  }

  protected void givenPermissions(String... permissions) {
    when(identityPermissions.myPermissions())
        .thenReturn(new IdentityPermissionsClient.Permissions(null, List.of(permissions)));
  }
}
