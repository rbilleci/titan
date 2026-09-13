# Third-party notices

Project-owned source, documentation, examples, and build logic are licensed under
GPL-3.0-only; see [LICENSE](LICENSE). No additional linking exception is granted.
Preserve the notices below when redistributing the project.

## Gradle wrappers

The root and IntelliJ build wrappers (`gradlew`, `gradlew.bat`, and
`gradle/wrapper/gradle-wrapper.jar` in each build) are Gradle tooling, licensed
under Apache License 2.0. Their existing headers remain intact; a copy of that
license is in `licenses/Apache-2.0.txt`. Wrapper JARs are not part of Titan's
published library JARs.

## Titan DSL

The separately maintained [Titan DSL](https://github.com/rbilleci/titan-dsl)
repository supplies the DSL/annotations, schema introspection, catalog generator,
and generation-only Gradle plugin. It is licensed GPL-3.0-only, with its own
third-party notices. Core consumes these artifacts rather than copying their source.

## Resolved dependencies and distributions

Versions are declared in `gradle/libs.versions.toml` and the standalone IntelliJ
build. Dependencies keep their own licenses. The normal Maven library/plugin JARs
are not shaded distributions of all resolved dependencies.

- `titan-transpiler` uses the JDK compiler API and the separate codegen artifact.
- `titan-gradle-plugin` uses the Gradle API, codegen plugin, transpiler, and
  Testcontainers for scratch-database verification. JDBC drivers are supplied
  through the consumer's `titanJdbc` configuration.
- `titan-runtime-jdbc` currently includes JUnit API, Testcontainers, PostgreSQL,
  and MySQL driver dependencies at runtime, as well as the DSL. It is not a
  dependency-free executor. PostgreSQL and MySQL drivers have distinct license
  terms; review the resolved dependency inventory for your distribution.
- Unit/build tooling includes JUnit, Mockito, ArchUnit, Error Prone, and JMH.
- The IntelliJ experiment uses the JetBrains Gradle plugin/platform SDK and
  JUnit 4. A plugin ZIP can bundle dependencies and requires a separate distribution
  review; no Marketplace release is made by the core build.
- Database container images used by integration tests and scratch verification
  are fetched separately. Their licenses and operational requirements are not
  replaced by Titan's project license.

Before distributing a bundled runtime, plugin ZIP, container image, or development
environment, inventory the exact resolved artifacts and include any additional
required license/notice texts. This file is not a claim that one project license
relicenses third-party dependencies.
