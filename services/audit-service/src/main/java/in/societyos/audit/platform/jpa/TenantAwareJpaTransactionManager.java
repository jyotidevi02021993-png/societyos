package in.societyos.audit.platform.jpa;

import in.societyos.audit.platform.core.tenant.Tenant;
import in.societyos.audit.platform.core.tenant.TenantContext;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.sql.PreparedStatement;
import java.util.UUID;
import java.util.stream.Collectors;
import org.hibernate.Session;
import org.springframework.orm.jpa.EntityManagerHolder;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Sets the PostgreSQL RLS variables at the start of every transaction:
 *
 * <pre>
 *   app.society_ids       = societies the actor may read  (uuid[])
 *   app.write_society_id  = the one society writes go to (uuid)
 * </pre>
 *
 * Both are set with {@code set_config(name, value, true)}, i.e. transaction-local, so a pooled
 * connection never carries a tenant into the next transaction. With no tenant bound, both are
 * empty and every tenant table returns zero rows.
 */
public class TenantAwareJpaTransactionManager extends JpaTransactionManager {

  private static final String SQL =
      "select set_config('app.society_ids', ?, true), set_config('app.write_society_id', ?, true)";

  public TenantAwareJpaTransactionManager(EntityManagerFactory emf) {
    super(emf);
  }

  @Override
  protected void doBegin(Object transaction, TransactionDefinition definition) {
    super.doBegin(transaction, definition);
    EntityManagerHolder holder =
        (EntityManagerHolder) TransactionSynchronizationManager.getResource(obtainEntityManagerFactory());
    if (holder == null) {
      return;
    }
    Tenant tenant = TenantContext.optional().orElse(null);
    String readable = tenant == null ? "{}" : toArrayLiteral(tenant);
    String write =
        tenant == null || tenant.activeSocietyId() == null ? "" : tenant.activeSocietyId().toString();
    EntityManager em = holder.getEntityManager();
    em.unwrap(Session.class)
        .doWork(
            connection -> {
              try (PreparedStatement ps = connection.prepareStatement(SQL)) {
                ps.setString(1, readable);
                ps.setString(2, write);
                ps.execute();
              }
            });
  }

  private static String toArrayLiteral(Tenant tenant) {
    return tenant.readableSocietyIds().stream()
        .map(UUID::toString)
        .collect(Collectors.joining(",", "{", "}"));
  }
}
