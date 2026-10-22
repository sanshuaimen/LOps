package com.cifa.arch;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition;

/**
 * P3 leakage prevention, mechanically enforced: the eval pipeline measures the
 * exam — it must have no path into the failure memory ("错题本"). Memory grows
 * only through explicit seeding (MemoryCommand) outside evaluation.
 */
@AnalyzeClasses(packages = "com.cifa", importOptions = ImportOption.DoNotIncludeTests.class)
class MemoryIsolationTest {

    @ArchTest
    static final ArchRule evalNeverWritesTheNotebook = ArchRuleDefinition.noClasses()
            .that().resideInAnyPackage("com.cifa.eval..")
            .should().dependOnClassesThat().resideInAnyPackage("com.cifa.memory..")
            .because("eval fixtures and goldens must never flow into the failure memory "
                    + "(plans.md §八: 评测数据不得进入 failure_case/RAG 索引)");

    @ArchTest
    static final ArchRule memoryStoreIsTheOnlyWriter = ArchRuleDefinition.classes()
            .that().resideInAPackage("com.cifa.memory..")
            .should().onlyHaveDependentClassesThat().resideInAnyPackage(
                    "com.cifa.memory", "com.cifa.agent", "com.cifa.service", "com.cifa.cli")
            .allowEmptyShould(true);
}
