plugins {
    `java-gradle-plugin`
    `maven-publish`
}

gradlePlugin {
    plugins {
        create("titanGradlePlugin") {
            id = "io.titan.gradle"
            implementationClass = "io.titan.gradle.TitanGradlePlugin"
        }
    }
}

dependencies {
    implementation(project(":titan-transpiler"))
    // The introspect/generate tasks import io.titan.catalog / io.titan.introspect directly.
    // titan-transpiler exposes titan-codegen only as an implementation dependency, so the edge
    // is not transitive — declare it explicitly here.
    implementation("io.titan:titan-codegen:0.1.0")
    implementation("io.titan:titan-codegen-gradle-plugin:0.1.0")
    // titanVerifyInstall provisions scratch verification databases as generic containers
    // (plan 4.4), mirroring the container-backed DDL introspection (G-6/G-7). Only the core
    // artifact: JDBC drivers come from the titanJdbc configuration.
    implementation(libs.testcontainers.core)

    // titan-dsl on the TEST classpath only: the functional tests locate the titan-dsl jar via
    // Class.forName("titan.dsl.StoredProcedure").getCodeSource() and hand it to the GradleRunner
    // fixture projects (whose user code imports titan.dsl.* and whose generated catalog sources
    // extend titan.dsl.Table). Pre-split this arrived transitively via transpiler/codegen's runtime
    // classpath; those are now testImplementation, so declare it explicitly. Resolves to the live
    // ../titan-dsl composite build.
    testImplementation("io.titan:titan-dsl:0.1.0")
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.mysql)
    testImplementation(libs.postgresql)
    testImplementation(libs.mysql.connector.j)
    testImplementation(gradleTestKit())
}

// java-gradle-plugin + maven-publish create the pluginMaven publication and the plugin-id
// marker publication automatically; only POM metadata is layered on here. No remote
// repository is configured — publishing targets mavenLocal only for now.
publishing {
    publications.withType<MavenPublication>().configureEach {
        pom {
            name.set("Titan Gradle Plugin")
            description.set("Gradle plugin driving the Titan pipeline: introspect, generate, transpile, package, verify.")
        }
    }
}
