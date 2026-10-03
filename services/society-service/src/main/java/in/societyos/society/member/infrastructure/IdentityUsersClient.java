package in.societyos.society.member.infrastructure;

import java.util.UUID;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/** identity-service {@code /v1/users}; the caller's token is forwarded, so identity checks their permission. */
@FeignClient(name = "identity-service", contextId = "identityUsers", path = "/v1/users")
public interface IdentityUsersClient {

  record ResolveRequest(String phone, String name) {}

  record Resolved(UUID userId) {}

  @PostMapping("/resolve")
  Resolved resolve(@RequestBody ResolveRequest request);
}
