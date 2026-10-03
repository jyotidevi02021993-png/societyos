package in.societyos.dashboard;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import in.societyos.dashboard.platform.test.SosArchitectureRules;

@AnalyzeClasses(packages = "in.societyos.dashboard", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

  @ArchTest static final ArchRule noKafka = SosArchitectureRules.NO_DIRECT_KAFKA;
  // @ArchTest static final ArchRule controllers = SosArchitectureRules.CONTROLLERS_USE_CASES_ONLY;
  // Enable both once the service has controllers and entities:
  // @ArchTest static final ArchRule entities = SosArchitectureRules.ENTITIES_ARE_TENANT_SCOPED;
}
