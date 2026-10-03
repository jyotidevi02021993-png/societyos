package in.societyos.society.platform.security;

import java.util.List;
import java.util.UUID;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * identity-service, found through Eureka. Called with the user's own token (forwarded by
 * {@link TenantForwardingInterceptor}), so it answers for the caller in the active society.
 */
@FeignClient(name = "identity-service", contextId = "identityPermissions", path = "/v1/me")
public interface IdentityPermissionsClient {

  record Permissions(UUID societyId, List<String> permissions) {}

  @GetMapping("/permissions")
  Permissions myPermissions();
}
