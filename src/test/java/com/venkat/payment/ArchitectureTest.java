package com.venkat.payment;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.RestController;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Architectural fitness tests verifying layering and dependency constraints.
 */
@AnalyzeClasses(packages = "com.venkat.payment", importOptions = {ImportOption.DoNotIncludeTests.class})
public class ArchitectureTest {

    @ArchTest
    public static final ArchRule controllersShouldNotDependOnRepositories = noClasses()
            .that().resideInAPackage("..api..")
            .should().dependOnClassesThat().resideInAPackage("..repository..");

    @ArchTest
    public static final ArchRule controllersShouldNotBeTransactional = classes()
            .that().areAnnotatedWith(RestController.class)
            .or().areAnnotatedWith(Controller.class)
            .should().notBeAnnotatedWith(Transactional.class);

    @ArchTest
    public static final ArchRule controllerMethodsShouldNotBeTransactional = methods()
            .that().areDeclaredInClassesThat().areAnnotatedWith(RestController.class)
            .or().areDeclaredInClassesThat().areAnnotatedWith(Controller.class)
            .should().notBeAnnotatedWith(Transactional.class);

    @ArchTest
    public static final ArchRule gatewayImplementationsShouldNotBeTransactional = classes()
            .that().resideInAPackage("..gateway..")
            .should().notBeAnnotatedWith(Transactional.class);
}

