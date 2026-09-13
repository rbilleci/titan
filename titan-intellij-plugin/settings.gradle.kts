// titan-intellij-plugin is a STANDALONE Gradle build (isolated from the root titan build).
//
// Why isolated: the org.jetbrains.intellij 1.x plugin uses Gradle internals removed in Gradle 9,
// so it cannot be part of the root build once that moves to Gradle 9.5.1 (needed to run under
// JDK 25). This module is pure IDE developer tooling with no dependency on the other Titan modules
// and is consumed by no build/runtime pipeline, so isolating it costs nothing. It stays on its own
// Gradle 8.10.2 wrapper (built with JDK 17 for IntelliJ 2024.1). Migrating it to
// intellij-platform-gradle-plugin 2.x + Gradle 9 is a possible future task.
rootProject.name = "titan-intellij-plugin"
