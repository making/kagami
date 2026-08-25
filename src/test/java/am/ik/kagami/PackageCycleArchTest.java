package am.ik.kagami;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

@AnalyzeClasses(packages = "am.ik.kagami", importOptions = ImportOption.DoNotIncludeTests.class)
class PackageCycleArchTest {

	@ArchTest
	static final ArchRule packagesAreFreeOfCycles = slices().matching("am.ik.kagami.(*)..").should().beFreeOfCycles();

}
