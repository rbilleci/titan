plugins {
    java
    `maven-publish`
}

val benchmark by sourceSets.creating {
    compileClasspath += sourceSets["main"].output + configurations["testRuntimeClasspath"]
    runtimeClasspath += output + compileClasspath
}

configurations[benchmark.implementationConfigurationName].extendsFrom(configurations["implementation"])
configurations[benchmark.runtimeOnlyConfigurationName].extendsFrom(configurations["runtimeOnly"])

dependencies {
    implementation("io.titan:titan-dsl:0.1.0")
    implementation(libs.junit.jupiter.api)
    implementation(libs.testcontainers.core)
    implementation(libs.testcontainers.postgresql)
    implementation(libs.testcontainers.mysql)
    implementation(libs.postgresql)
    implementation(libs.mysql.connector.j)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.mockito.core)
    testImplementation(libs.archunit.junit5)

    add(benchmark.implementationConfigurationName, libs.jmh.core)
    add(benchmark.annotationProcessorConfigurationName, libs.jmh.generator.annprocess)
}

tasks.register<Test>("benchmarkTest") {
    description = "Runs lightweight benchmark sanity tests."
    group = "verification"
    testClassesDirs = benchmark.output.classesDirs
    classpath = benchmark.runtimeClasspath
    useJUnitPlatform()
}

tasks.register<JavaExec>("jmh") {
    description = "Runs Titan benchmark suite with JMH."
    group = "benchmark"
    classpath = benchmark.runtimeClasspath
    mainClass.set("org.openjdk.jmh.Main")
    args = listOf("io.titan.runtime.benchmark.*")
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            pom {
                name.set("Titan Runtime JDBC")
                description.set("Titan's JDBC runtime: query execution, testing extension, and live-database harness support.")
            }
        }
    }
}
