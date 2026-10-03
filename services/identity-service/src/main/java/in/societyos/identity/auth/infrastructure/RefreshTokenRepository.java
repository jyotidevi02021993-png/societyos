package in.societyos.identity.auth.infrastructure;

import in.societyos.identity.auth.domain.RefreshToken;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

  /** Locks the row so two concurrent refreshes with the same token cannot both succeed. */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  Optional<RefreshToken> findByTokenHash(String tokenHash);

  @Modifying
  @Query(
      "update RefreshToken t set t.revokedAt = CURRENT_TIMESTAMP "
          + "where t.deviceId = :deviceId and t.revokedAt is null")
  int revokeAllForDevice(@Param("deviceId") UUID deviceId);

  @Modifying
  @Query(
      "update RefreshToken t set t.revokedAt = CURRENT_TIMESTAMP "
          + "where t.userId = :userId and t.revokedAt is null")
  int revokeAllForUser(@Param("userId") UUID userId);
}
