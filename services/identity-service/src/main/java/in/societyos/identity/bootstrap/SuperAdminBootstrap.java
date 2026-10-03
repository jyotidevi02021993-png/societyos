package in.societyos.identity.bootstrap;

import in.societyos.identity.config.IdentityProperties;
import in.societyos.identity.user.domain.AppUser;
import in.societyos.identity.user.infrastructure.AppUserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Creates the first platform admin from {@code SOS_BOOTSTRAP_ADMIN_EMAIL/PASSWORD} when no admin
 * exists yet. The admin must enrol TOTP at first login ({@code mfaSetupRequired}).
 */
@Component
class SuperAdminBootstrap implements ApplicationRunner {

  private static final Logger log = LoggerFactory.getLogger(SuperAdminBootstrap.class);

  private final IdentityProperties props;
  private final AppUserRepository users;
  private final PasswordEncoder passwords;
  private final TransactionTemplate tx;

  SuperAdminBootstrap(
      IdentityProperties props, AppUserRepository users, PasswordEncoder passwords, PlatformTransactionManager tm) {
    this.props = props;
    this.users = users;
    this.passwords = passwords;
    this.tx = new TransactionTemplate(tm);
  }

  @Override
  public void run(ApplicationArguments args) {
    IdentityProperties.BootstrapAdmin admin = props.bootstrapAdmin();
    if (admin == null || !admin.configured()) {
      return;
    }
    tx.executeWithoutResult(
        s -> {
          if (users.existsByPlatformRole(AppUser.SUPER_ADMIN)) {
            return;
          }
          AppUser user =
              users.findByEmailIgnoreCase(admin.email())
                  .orElseGet(() -> AppUser.admin(admin.email(), admin.name(), passwords.encode(admin.password())));
          user.grantPlatformAdmin();
          users.save(user);
          log.info("Bootstrap platform admin created for {}", admin.email());
        });
  }
}
