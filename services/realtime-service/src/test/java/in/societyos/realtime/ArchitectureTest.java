package in.societyos.realtime;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import in.societyos.realtime.platform.test.SosArchitectureRules;

@AnalyzeClasses(packages = "in.societyos.realtime", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

  @ArchTest static final ArchRule noKafka = SosArchitectureRules.NO_DIRECT_KAFKA;
  // CONTROLLERS_USE_CASES_ONLY stays off: this service has no REST controllers (push only), and
  // ArchUnit fails a rule that matches no classes. No JPA entities either (no database).
  @ArchTest static final ArchRule domainIsPure = SosArchitectureRules.DOMAIN_IS_PURE;
}
