# Titan developer guide

This guide covers the current compiler and Gradle pipeline. Use the
[quickstart](../examples/quickstart/README.md) for a complete working project.
Titan is a supported-subset compiler, not an arbitrary-Java runtime.

## 1. Source setup

Use JDK 21, this repository, and the sibling
[Titan DSL repository](https://github.com/rbilleci/titan-dsl). Core uses a Gradle
composite to consume the DSL, codegen, and generation plugin from source; no
pre-existing Maven-local artifacts are required.

For an application beside both repositories:

```kotlin
// settings.gradle.kts
pluginManagement { includeBuild("../titan") }
rootProject.name = "my-app"
includeBuild("../titan")
```

```kotlin
// build.gradle.kts
plugins {
    java
    id("io.titan.gradle")
}
repositories { mavenCentral() }
java { toolchain { languageVersion.set(JavaLanguageVersion.of(21)) } }
dependencies {
    implementation("io.titan:titan-dsl:0.1.0")
    compileOnly("org.jspecify:jspecify:1.0.0")
    titanJdbc("org.postgresql:postgresql:42.7.4")
}
titan {
    database {
        dialect.set("postgresql")
        schemas.set(listOf("app"))
        ddlDir.set("src/main/resources/db/schema")
        ddlMode.set("parser")
    }
    catalog { targetPackage.set("generated.catalog") }
    transpiler {
        targets.set(listOf("postgresql"))
        sqlSafety.set("strict")
    }
}
```

The parser mode is explicitly limited. Use live JDBC or scratch-container DDL
introspection for schemas beyond its supported subset. See [schema sourcing](schema-sourcing-guide.md).

## 2. Entry points and input styles

Annotate static methods with `@StoredProcedure` or `@StoredFunction`. Procedures
return void; functions use supported return shapes. `@Trigger`,
`@ScheduledJob`, `@ViewDefinition`, and `@SQL` provide additional compiler
surfaces. Annotations live in the DSL dependency.

- **JDBC:** prepared-statement bindings, recognized read/update/cursor shapes, and
  supported Java control flow are lowered to native SQL for one chosen dialect.
  A Connection/DataSource used in a recognized infrastructure shape is not a SQL
  routine parameter. See the [JDBC guide](transpilable-jdbc-subset.md).
- **Static DSL:** supported select/join/filter/group/window/CTE/DML expressions
  lower through structured SQL nodes for PostgreSQL and MySQL.
- **Raw SQL:** explicit escape hatches carry dialect and SQL-safety limitations.
  Successful Java compilation does not validate arbitrary raw SQL.

The independent DSL runtime API is broader/different in places. Do not assume
`DSL.using(...).select(...).render()` can be transpiled because it renders SQL
successfully in a JVM application. Compiler examples use the supported static
DSL forms and terminal operations.

## 3. Pipeline and artifacts

Run the wrapper from your application directory:

```bash
../titan/gradlew titanPackage
../titan/gradlew titanJdbcCompatReport titanPermissiveScopesReport
```

The plugin wires generation into Java compilation. Transpilation depends on
generation and compilation; packaging depends on transpilation.

| Output | Default location |
| --- | --- |
| Introspected schema | `build/titan/schema.json` |
| Generated Java catalog | `build/generated/sources/titan/` |
| SQL by target | `build/generated/sql/titan/<dialect>/` |
| Repeatable migration bundles and metadata | `build/titan/migrations/` |
| Compatibility / permissive-scope reports | `build/reports/titan/` |

The package describes objects and dependencies from structured compiler metadata.
It includes artifact/install information and reverse-order rollback SQL.
Application tables/migrations remain application-owned; introspecting their schema
is not the same as packaging their DDL.

`deployment.mode` selects `migration` or `direct` output packaging. Neither mode
deploys merely by running `titanPackage`. Output defaults to the build directory;
writing migrations into source resources is an explicit opt-in.

## 4. SQL safety and semantic limits

- Keep `transpiler.sqlSafety` at `strict` by default. Prefer parameter binding to
  constructing SQL from values.
- `@SqlSafety(PERMISSIVE)` can relax a method/class, and a build-level setting can
  relax wider scopes. These are explicit trust-boundary exceptions, not sanitizers.
  Review the effective-permissive-scope report and every raw fragment.
- The older `strictMode` option is a deprecated no-op. It is not a security switch.
- Validation rejects many unsupported constructs with source-position diagnostics,
  but compiler success is not a proof of semantic equivalence or SQL validity.
- Java integer overflow differs from SQL by default: fail-loud arithmetic is used.
  `strictWraparound` enables supported int wraparound emulation. Decimal precision,
  time-zone ranges, null behavior, collation, and SQL modes also deserve explicit tests.
- `@SecurityDefiner` changes privilege context. Review generated grants, ownership,
  schema/search-path assumptions, and caller access before deployment.
- Values can appear in SQL literals, exception text, reports, and application logs.
  Do not feed secrets into source constants or publish sensitive generated artifacts.

See [SECURITY](../SECURITY.md) and the [security review notes](security-review-sql-text-safety.md).

## 5. Supported Java: practical starting points

Supported subsets include local variables, expressions, conditionals, loops,
returns, exceptions, static helper calls, and selected scalar/time/decimal APIs.
This does not mean every overload or combination is supported. Arbitrary object
graphs, framework services, reflection, network/file I/O, and general application
execution do not become database operations.

Keep routines small enough to review and test. Use source-local static helpers
with supported parameter/return types; do not introduce object-instance call
chains expecting the compiler to emulate the JVM.

For a supported-subset diagnosis, inspect positioned errors and the relevant
tests under `titan-transpiler/src/test/java/io/titan/transpiler/`. Golden output,
live deployability, null/exception analysis, and differential tests provide
different evidence; none substitutes for testing the application's workload.

## 6. Installation verification is mutating

```bash
../titan/gradlew titanVerifyInstall
```

Without a verification JDBC URL, this provisions scratch Docker containers.
With a configured URL, it installs SQL into that database. Use a disposable
verification environment and narrowly scoped credentials. Never configure a
production target just to try the command.

For a managed verification target, provide the URL/user/password through
environment-backed Gradle providers:

```kotlin
titan {
    verification {
        dialect.set("postgresql")
        jdbcUrl.set(providers.environmentVariable("TITAN_VERIFY_URL"))
        username.set(providers.environmentVariable("TITAN_VERIFY_USER"))
        password.set(providers.environmentVariable("TITAN_VERIFY_PASSWORD"))
    }
}
```

Review all generated install/rollback SQL, extensions, schema prerequisites, and
data-loss implications first. Scheduled jobs may require separately managed
scheduler entries; a rollback script is not a database backup.

## 7. Application integration

A transpiled routine is not automatically installed or substituted for its Java
method at application startup. Deploy reviewed SQL through your migration/release
process, then call it through your application's JDBC connection or an appropriate
adapter. Keep transaction boundaries, error translation, row mapping, and pool
management explicit.

The `titan-runtime-jdbc` artifact contains both execution and testing helpers and
has JDBC-driver/Testcontainers/JUnit runtime dependencies. Do not describe it as a
minimal dependency-free executor. Applications may instead call generated SQL
through their existing JDBC stack.

See [application integration](adoption-runtime-integration-guide.md).

## 8. Troubleshooting

- Missing sibling checkout: clone Titan DSL beside Titan, or set `titanDslDir`.
- Plugin ID cannot resolve: include the Titan source build in `pluginManagement`;
  do not assume an unpublished plugin is available from the Plugin Portal.
- DDL parser rejects a statement: use the container/live schema source; do not
  silently remove schema features just to make generation pass.
- No Docker: the default core build and parser quickstart work without it;
  container introspection and install/integration verification do not.
- Unsupported JDBC/Java shape: use the report and positioned diagnostic, reduce
  the example, and choose a supported rewrite or explicit reviewed SQL.
- MySQL function fails on dynamic SQL: use a supported procedure shape or static
  SQL; dynamic PREPARE/EXECUTE is not a universal function/trigger implementation.
- Stale outputs: use the current source composite and a clean generated output
  directory. Do not edit generated catalog files manually.
- SQL errors: inspect the emitted `-- titan:source:` markers and original Java;
  retain a small reproducer and report exact database/JDK/build versions.

For contributor commands and test reports, see [testing](testing.md).
