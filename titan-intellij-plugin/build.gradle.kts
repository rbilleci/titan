import java.util.Properties

// Standalone experimental build: Gradle 8, Java 17, and JUnit 4, independent of
// the core Java 21 build. See ../docs/intellij-plugin-bootstrap.md.
plugins {
    id("java")
    // Inlined (this is a standalone build with no access to the root version catalog): the
    // org.jetbrains.intellij 1.x plugin pins this module to Gradle 8.x, which is why it is isolated
    // from the Gradle-9 root build. See settings.gradle.kts.
    id("org.jetbrains.intellij") version "1.17.4"
}

group = "io.titan"
val coreVersion = Properties().apply {
    file("../gradle.properties").inputStream().use { load(it) }
}
version = coreVersion.getProperty("titanVersion")

tasks.withType<Jar>().configureEach {
    from(files("../LICENSE", "../THIRD_PARTY_NOTICES.md")) { into("META-INF") }
}

repositories {
    mavenCentral()
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}

intellij {
    version.set("2024.1")
    type.set("IC")
    plugins.set(listOf("com.intellij.java"))
    // A distributable plugin requires separate instrumentation/compatibility validation.
    // Local source tests run without instrumentation; this is not a Marketplace release.
    instrumentCode.set(false)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.withType<Test>().configureEach {
    useJUnit()
}

tasks.patchPluginXml {
    sinceBuild.set("241")
    untilBuild.set("241.*")
}
