// Experimental management records and file/JDBC stores. The generated routine
// bundle is packaged as resources, not a runtime project dependency.
plugins {
    java
    `maven-publish`
}

dependencies {
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)

    // B5 dogfood IT (@Tag("docker")): Testcontainers (PG + MySQL) + the JDBC drivers, TEST scope only
    // — the main classpath stays on the JDK's java.sql/javax.sql alone (no runtime DB dependency, per
    // the Phase B design). The IT compiles against the Testcontainers/driver types directly, so they
    // are declared here from the version catalog. (titan-runtime-jdbc is intentionally NOT added: its
    // io.titan.runtime.testing harness is package-private, and a project dependency on it would, like
    // the routines-bundle wiring below, drag titan-runtime-jdbc's internal project() edges into the
    // titan-graphql consumer's Quarkus configuration-time dependency walk — TG-BLK-009. The IT wires
    // its own containers with the same minimal pattern as the routines module's deployability probe.)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.mysql)
    testImplementation(libs.postgresql)
    testImplementation(libs.mysql.connector.j)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// B3 — ship the Titan-transpiled SQL bundle as titan-management's OWN resource.
//
// titan-management loads io/titan/management/sql/{postgresql,mysql}/{schema,routines}.sql (schema DDL
// first, then the transpiled routines) from the classpath via ManagementSchemaInstaller. The bundle
// is produced by titan-management-routines' `transpileManagementRoutines` task. We pull it in as a
// processResources INPUT (a cross-project TASK dependency), NOT as a `dependencies { project(...) }`
// edge.
//
// WHY NOT a project() dependency (the consumer-safety reason): titan-graphql depends on the published
// io.titan:titan-management, and its Quarkus plugin walks the configuration-time project-dependency
// graph (QuarkusPlugin.afterEvaluate -> visitProjectDependencies) calling ProjectDependency.getPath()
// — a Gradle 8.11 API absent in core's 8.10.2 wrapper (TG-BLK-009). A `implementation(project(
// ":titan-management-routines"))` here would add exactly such an edge (and recurse into the routines
// module's own project(":titan-dsl")/project(":titan-transpiler") edges), re-breaking the consumer
// build with a NoSuchMethodError at configuration time. A task-output input adds no ProjectDependency
// to any configuration, so the consumer's walk never sees the routines module — while the SQL bundle
// still lands inside titan-management.jar at the io/titan/management/sql/... resource path.
//
// No dependency cycle and one-way flow are preserved: the SQL artifact flows INTO titan-management;
// titan-management-routines depends only on titan-dsl and never back on titan-management.
// Ensure the routines module is configured before we reference its task (cross-project task access).
evaluationDependsOn(":titan-management-routines")
val managementRoutinesBundle = project(":titan-management-routines")
    .tasks.named("transpileManagementRoutines")

tasks.named<ProcessResources>("processResources") {
    from(managementRoutinesBundle)
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            pom {
                name.set("Titan Management (experimental)")
                description.set("Experimental Titan management records and file/JDBC stores, with generated database routines.")
            }
        }
    }
}
