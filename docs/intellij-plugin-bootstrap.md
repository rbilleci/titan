# IntelliJ experiment

`titan-intellij-plugin/` is a standalone build targeting IntelliJ IDEA 2024.1
(build family 241), with Java 17 compatibility and its own Gradle 8.10.2 wrapper.
It is not part of the Java 21 / Gradle 9 core build.

Implemented scaffolding includes Java DSL completion, unsupported-feature
inspection, and SQL-preview navigation. This is experimental tooling, not a
published or supported Marketplace release.

From the repository root:

```bash
./titan-intellij-plugin/gradlew -p titan-intellij-plugin test
```

The first run downloads the IntelliJ platform/tooling and may require substantial
disk space. Core builds and examples do not require it.

Instrumentation is disabled in the existing build configuration. Do not treat
a passing core build, IDE unit tests, or an unreviewed plugin ZIP as distribution
certification. Before a plugin release, verify compatible IDE/JDK versions,
instrumentation, plugin verification, bundled dependency notices, and signing/
publication requirements independently.

Repository issues are the project contact; no unverified vendor email address is
advertised.
