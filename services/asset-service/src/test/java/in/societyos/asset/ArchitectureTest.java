package in.societyos.asset;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import in.societyos.asset.platform.test.SosArchitectureRules;

@AnalyzeClasses(packages = "in.societyos.asset", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

  @ArchTest static final ArchRule noKafka = SosArchitectureRules.NO_DIRECT_KAFKA;
  @ArchTest static final ArchRule controllers = SosArchitectureRules.CONTROLLERS_USE_CASES_ONLY;
  @ArchTest static final ArchRule entities = SosArchitectureRules.ENTITIES_ARE_TENANT_SCOPED;
}
