package in.societyos.security.platform.test;

import in.societyos.security.platform.core.tenant.Tenant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

/** Helpers to act as a user of a society in tests. */
public final class TestTenants {

  private TestTenants() {}

  public static Tenant user(UUID userId, UUID societyId, String... roles) {
    return new Tenant(userId, Tenant.ActorType.USER, societyId, List.of(societyId), Set.of(roles), null);
  }

  /** MockMvc JWT with the SocietyOS claims ({@code sub}, {@code sid}, {@code sids}, {@code roles}). */
  public static RequestPostProcessor jwtFor(UUID userId, UUID societyId, String... roles) {
    return jwt().jwt(
            (Jwt.Builder b) ->
                b.subject(userId.toString())
                    .claim("sid", societyId.toString())
                    .claim("sids", List.of(societyId.toString()))
                    .claim("roles", List.of(roles)));
  }
}
