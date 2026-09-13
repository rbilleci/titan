package io.titan.runtime.testing;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

class RuntimeJdbcModuleBoundaryTest {

    @Test
    void runtimeJdbcMustNotDependOnTranspilerInternals() {
        var imported = new ClassFileImporter().importPackages("io.titan.runtime");

        noClasses()
                .that().resideInAPackage("io.titan.runtime..")
                .should().dependOnClassesThat()
                .resideInAnyPackage("io.titan.transpiler..")
                .check(imported);
    }
}
