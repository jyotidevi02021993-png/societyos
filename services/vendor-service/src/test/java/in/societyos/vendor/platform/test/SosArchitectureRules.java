package in.societyos.vendor.platform.test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.lang.ArchRule;
import in.societyos.vendor.platform.jpa.GlobalEntity;
import in.societyos.vendor.platform.jpa.TenantEntity;
import jakarta.persistence.Entity;

/**
 * The architecture rules from docs/architecture/07 §2. A service applies them with:
 *
 * <pre>{@code
 * @AnalyzeClasses(packages = "in.societyos.gate")
 * class ArchitectureTest {
 *   @ArchTest static final ArchRule entities = SosArchitectureRules.ENTITIES_ARE_TENANT_SCOPED;
 *   ...
 * }
 * }</pre>
 */
public final class SosArchitectureRules {

  private SosArchitectureRules() {}

  public static final ArchRule DOMAIN_IS_PURE =
      noClasses()
          .that().resideInAPackage("..domain..")
          .should().dependOnClassesThat()
          .resideInAnyPackage("org.springframework..", "jakarta.persistence..", "..infrastructure..", "..api..")
          .because("the domain has no framework dependencies");

  public static final ArchRule CONTROLLERS_USE_CASES_ONLY =
      noClasses()
          .that().resideInAPackage("..api..")
          .should().dependOnClassesThat()
          .resideInAPackage("..infrastructure..")
          .because("controllers go through a use case, never a repository adapter");

  public static final ArchRule NO_DIRECT_KAFKA =
      noClasses()
          .that().resideOutsideOfPackage("..platform..")
          .should().dependOnClassesThat()
          .haveFullyQualifiedName("org.springframework.kafka.core.KafkaTemplate")
          .because("events are published only through the outbox (DomainEvents.publish)");

  public static final ArchRule ENTITIES_ARE_TENANT_SCOPED =
      classes()
          .that().areAnnotatedWith(Entity.class)
          .should().beAssignableTo(TenantEntity.class)
          .orShould().beAssignableTo(GlobalEntity.class)
          .because("every row carries society_id (GlobalEntity only for reviewed global tables)");
}
