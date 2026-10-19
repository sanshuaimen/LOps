package com.cifa.security;

import com.tngtech.archunit.core.domain.JavaAccess;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;

import java.nio.file.Files;
import java.util.Set;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Structural zero tolerance for the model-reachable surface: no network, no
 * process execution, and no file writes anywhere in the tool / policy / git
 * packages. GitCommandExecutor is the single, deliberately-audited process
 * surface; everything else is locked out by package boundary, not by review.
 */
@AnalyzeClasses(packages = "com.cifa", importOptions = ImportOption.DoNotIncludeTests.class)
class SecurityArchitectureTest {

    private static final String[] MODEL_REACHABLE = {"com.cifa.tool..", "com.cifa.security.."};
    private static final Set<String> FILE_WRITE_METHODS = Set.of(
            "write", "writeString", "createDirectory", "createDirectories",
            "delete", "deleteIfExists", "copy", "move", "newBufferedWriter", "newOutputStream");

    @ArchTest
    static final ArchRule toolsAndPolicyNeverOpenNetworkOrProcessSurfaces = noClasses()
            .that().resideInAnyPackage(MODEL_REACHABLE)
            .should(new ArchCondition<>("stay free of HTTP clients and process execution") {
                @Override
                public void check(JavaClass clazz, ConditionEvents events) {
                    for (JavaAccess<?> access : clazz.getAccessesFromSelf()) {
                        String owner = access.getTarget().getOwner().getName();
                        if (owner.equals("java.net.http.HttpClient")
                                || owner.startsWith("java.net.http.HttpClient$")
                                || owner.equals("java.lang.ProcessBuilder")) {
                            events.add(SimpleConditionEvent.violated(access,
                                    clazz.getName() + " -> " + access.getTarget().getFullName()));
                        }
                    }
                }
            });

    @ArchTest
    static final ArchRule gitExecutorIsTheOnlyProcessSurface = noClasses()
            .that().resideInAnyPackage("com.cifa.tool..", "com.cifa.security..", "com.cifa.git..")
            .and().doNotHaveFullyQualifiedName("com.cifa.git.GitCommandExecutor")
            .should(new ArchCondition<>("not construct processes") {
                @Override
                public void check(JavaClass clazz, ConditionEvents events) {
                    for (JavaAccess<?> access : clazz.getAccessesFromSelf()) {
                        if (access.getTarget().getOwner().getName().equals("java.lang.ProcessBuilder")) {
                            events.add(SimpleConditionEvent.violated(access,
                                    clazz.getName() + " -> " + access.getTarget().getFullName()));
                        }
                    }
                }
            });

    @ArchTest
    static final ArchRule toolsGitAndPolicyNeverWriteFiles = noClasses()
            .that().resideInAnyPackage(MODEL_REACHABLE[0], MODEL_REACHABLE[1], "com.cifa.git..")
            .should(new ArchCondition<>("not write, delete, move or copy files") {
                @Override
                public void check(JavaClass clazz, ConditionEvents events) {
                    for (JavaAccess<?> access : clazz.getAccessesFromSelf()) {
                        if (access.getTarget().getOwner().isEquivalentTo(Files.class)
                                && FILE_WRITE_METHODS.contains(access.getTarget().getName())) {
                            events.add(SimpleConditionEvent.violated(access,
                                    clazz.getName() + " -> Files." + access.getTarget().getName()));
                        }
                    }
                }
            });
}
