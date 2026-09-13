pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    // Core dependencies resolve from Maven Central; the DSL is substituted below.
    repositories {
        mavenCentral()
    }
}

rootProject.name = "titan"

// titan-dsl and its codegen/plugin modules live in ../titan-dsl. Core consumes
// it via this composite build: Gradle substitutes the io.titan:titan-dsl coordinate with the live
// sibling build, so the core build still compiles against live DSL source with no publish step.
// Tests use the resolved DSL artifact directly; no Maven-local publication is required.
val titanDslDir = file(providers.gradleProperty("titanDslDir").orElse("../titan-dsl").get())
require(titanDslDir.resolve("settings.gradle.kts").isFile) {
    "Titan requires the Titan DSL source checkout. Clone https://github.com/rbilleci/titan-dsl " +
        "beside this repository, or pass -PtitanDslDir=/path/to/titan-dsl."
}
includeBuild(titanDslDir)

include("titan-runtime-jdbc")
include("titan-transpiler")
include("titan-management")
include("titan-management-routines")
include("titan-gradle-plugin")
// titan-intellij-plugin is a STANDALONE build (its own settings.gradle.kts + Gradle 8.10.2 wrapper):
// its org.jetbrains.intellij 1.x plugin uses Gradle internals removed in Gradle 9, and this root
// build runs Gradle 9.5.1 (JDK 25 support). The IDE plugin has no dependency on the other modules
// and is consumed by no pipeline, so isolating it is free. Build it via titan-intellij-plugin/gradlew.
