package in.societyos.billing.expense.infrastructure;

import in.societyos.billing.expense.domain.Budget;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface BudgetRepository extends JpaRepository<Budget, UUID> {

  List<Budget> findByFinancialYearOrderByCategoryAsc(String financialYear);

  @Query("select count(b) > 0 from Budget b where b.financialYear = :fy and upper(b.category) = upper(:category)"
      + " and b.status <> 'REJECTED'")
  boolean liveExists(String fy, String category);
}
