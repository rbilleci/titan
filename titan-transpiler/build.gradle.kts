plugins {
    java
    `maven-publish`
}

dependencies {
    // titan-dsl is a TEST-only dependency: after SqlSafetyMode was re-homed into the transpiler,
    // transpiler MAIN compiles against zero titan.dsl types (DSL annotations/types are recognized by
    // FQN string off the javac AST). The 62 DSL conformance tests + @SqlSafety fixtures need it.
    testImplementation("io.titan:titan-dsl:0.1.0")
    // Catalog code generation and schema introspection (io.titan.catalog / io.titan.introspect)
    // were extracted to titan-codegen so titan-dsl-side consumers can use them without the
    // transpiler. The transpiler depends on them only as an implementation detail.
    implementation("io.titan:titan-codegen:0.1.0")

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.mockito.core)
    // The transpiler integration tests drive PostgreSQLContainer/MySQLContainer, whose base
    // classes (GenericContainer/JdbcDatabaseContainer) live in testcontainers-core. Declared
    // directly rather than relied on transitively from the -postgresql/-mysql modules.
    testImplementation(libs.testcontainers.core)
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.mysql)
    testRuntimeOnly(libs.postgresql)
    // Keep the test driver aligned with the shared, security-reviewed version.
    testRuntimeOnly(libs.mysql.connector.j)
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            pom {
                name.set("Titan Transpiler")
                description.set("Titan's Java-to-SQL transpiler: lowering, TIR, and dialect emitters.")
            }
        }
    }
}
