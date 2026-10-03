package in.societyos.billing.ledger.infrastructure;

import in.societyos.billing.ledger.domain.LedgerAccount;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LedgerAccountRepository extends JpaRepository<LedgerAccount, UUID> {

  Optional<LedgerAccount> findByCode(String code);

  List<LedgerAccount> findAllByOrderByCodeAsc();
}
