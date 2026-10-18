package com.cifa.arch;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * P0 core stays framework-free: everything under com.cifa EXCEPT the P1 shell
 * (web / persistence / config) must not touch Spring. The rule is written by
 * exclusion on purpose — a new core package is covered automatically, so the
 * boundary cannot drift silently.
 */
@AnalyzeClasses(packages = "com.cifa", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    @ArchTest
    static final ArchRule corePackagesDoNotDependOnSpring = noClasses()
            .that().resideOutsideOfPackages("com.cifa.web..", "com.cifa.persistence..",
                    "com.cifa.config..", "com.cifa.app..",
                    // the root package holds only the @SpringBootApplication shell class
                    "com.cifa")
            .should().dependOnClassesThat()
            .resideInAnyPackage("org.springframework..", "com.baomidou..", "org.flywaydb..");

    @ArchTest
    static final ArchRule corePackagesDoNotDependOnServletApi = noClasses()
            .that().resideOutsideOfPackages("com.cifa.web..")
            .should().dependOnClassesThat().resideInAnyPackage("jakarta.servlet..", "org.apache.catalina..");
}
