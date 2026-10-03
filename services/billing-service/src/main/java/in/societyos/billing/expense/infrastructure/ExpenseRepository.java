package in.societyos.billing.expense.infrastructure;

import in.societyos.billing.expense.domain.Expense;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ExpenseRepository extends JpaRepository<Expense, UUID> {

  @Query("select e from Expense e where e.spentOn between :from and :to"
      + " and (:category = '' or upper(e.category) = upper(:category)) order by e.spentOn desc, e.createdAt desc")
  List<Expense> between(LocalDate from, LocalDate to, String category);

  interface CategoryTotal {
    String getCategory();

    long getTotal();
  }

  @Query("select upper(e.category) as category, sum(e.amountPaise) as total from Expense e"
      + " where e.financialYear = :fy group by upper(e.category)")
  List<CategoryTotal> totalsFor(String fy);
}
