package com.cifa.arch;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition;

/**
 * Evolution leakage prevention, mechanically enforced — the same discipline
 * MemoryIsolationTest applies to the notebook, extended to the behavior asset:
 * the eval package measures the exam; it must have no path into the skills
 * store. (Reading matched rules via {@code SkillsRouter} IS the legitimate
 * path; the write gate — append/promote/reject — must stay out of eval's
 * reach. The CLI owns the store, exactly like the memory wiring.)
 */
@AnalyzeClasses(packages = "com.cifa", importOptions = ImportOption.DoNotIncludeTests.class)
class EvolutionIsolationTest {

    @ArchTest
    static final ArchRule evalNeverWritesTheSkillStore = ArchRuleDefinition.noClasses()
            .that().resideInAnyPackage("com.cifa.eval..")
            .should().dependOnClassesThat().haveFullyQualifiedName("com.cifa.evolution.SkillsStore")
            .because("eval fixtures and gate reports must never flow into the behavior asset "
                    + "(the paired gate consumes reports the CLI loads; the store's write "
                    + "gates must stay out of eval's reach)");
}
