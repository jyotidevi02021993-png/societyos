package in.societyos.billing.platform.test;

import static org.mockito.Mockito.when;

import in.societyos.billing.platform.security.IdentityPermissionsClient;
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
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

/**
 * Base for integration tests: one Postgres, Kafka and Redis per JVM (singleton containers).
 *
 * <p>Flyway migrates as the superuser {@code test}; the application connects as {@code app}, a
 * plain role that does not own the tables, so row-level security is really enforced, exactly as
 * with {@code <svc>_app} in production. The outbox is relayed by the polling relay.
 *
 * <p>Tagged {@code integration}: run with {@code ./mvnw verify -Pintegration}.
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
public abstract class IntegrationTestBase {

  protected static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer("postgres:16-alpine")
          .withCommand("postgres", "-c", "wal_level=logical")
          .withCopyFileToContainer(
              MountableFile.forClasspathResource("sos-test-init.sql"), "/docker-entrypoint-initdb.d/10-app-role.sql");

  protected static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:3.8.1");

  @SuppressWarnings("resource")
  protected static final GenericContainer<?> REDIS =
      new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

  static {
    POSTGRES.start();
    KAFKA.start();
    REDIS.start();
  }

  @DynamicPropertySource
  static void containerProperties(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    r.add("spring.datasource.username", () -> "app");
    r.add("spring.datasource.password", () -> "app");
    r.add("spring.flyway.url", POSTGRES::getJdbcUrl);
    r.add("spring.flyway.user", POSTGRES::getUsername);
    r.add("spring.flyway.password", POSTGRES::getPassword);
    r.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    r.add("spring.data.redis.host", REDIS::getHost);
    r.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    r.add("sos.outbox.relay", () -> "polling");
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
