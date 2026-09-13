# Titan

[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)

**Write database routines in Java. Ship reviewable SQL.**

Titan compiles supported Java methods into PostgreSQL and MySQL stored procedures,
functions, triggers, and scheduled jobs. Keep source code in your Java project,
inspect the generated SQL, and package it with your database migrations.

Use ordinary JDBC patterns where supported, or the typed Titan DSL. Titan is a
compiler for a defined subset—not a way to run arbitrary Java inside a database,
an ORM replacement, or an automatic application migration.

```java
@StoredProcedure
public static void creditAccount(Connection connection, long accountId, BigDecimal amount)
        throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(
            "UPDATE app.accounts SET balance = balance + ? WHERE id = ?")) {
        statement.setBigDecimal(1, amount);
        statement.setLong(2, accountId);
        statement.executeUpdate();
    }
}
```

Titan turns this into a database procedure. The connection is infrastructure, not
a SQL parameter; the account ID and amount remain parameters. The
[complete quickstart](examples/quickstart/README.md) contains the imports, DDL,
build configuration, a scalar function, and automated output checks.

## Why use Titan?

- **Keep database-side logic in your Java workflow.** Factor supported logic into
  methods and helpers, review it alongside application code, and generate the SQL
  implementation rather than maintaining two hand-written versions.
- **Move a bounded workflow closer to its data.** Stored routines can consolidate
  work that otherwise requires repeated application/database exchanges. Measure
  the actual workload: Titan does not promise that moving code always makes it faster.
- **Start with a method, not a rewrite.** Supported JDBC statements and control
  flow can be lowered without converting the method to the DSL. Your application
  still owns connections, transactions, result mapping, and deployment policy.
- **Make generated behavior inspectable.** Source markers, diagnostics, SQL files,
  migration bundles, object metadata, and rollback scripts let you review what
  will run before deployment.
- **Use schema-derived types where they help.** The separate
  [Titan DSL project](https://github.com/rbilleci/titan-dsl) generates typed Java
  catalogs and supplies the DSL/annotations. Its runtime renderer works independently
  of Titan's compiler.

## Status and boundaries

This is an **early-access, source-consumable project**. APIs, generated SQL, and
package formats may change. No stable release or public registry publication is
announced by the current `0.1.0` build coordinate.

| Input or component | Current scope |
| --- | --- |
| Java routines using the static Titan DSL | Supported SQL-lowerable subset, with PostgreSQL/MySQL emission and positioned diagnostics. |
| Standard JDBC input | Implemented subset of prepared statements, bindings, reads, updates, cursor loops, and related Java control flow. Embedded SQL is native to one selected dialect, not automatically translated. |
| `@SQL` and raw SQL | Escape hatches with their own validation/safety rules; not proof that supplied SQL is safe or portable. |
| Runtime JDBC | Query execution and test/equivalence helpers; not a general-purpose ORM. |
| Management modules | Experimental records, file/JDBC stores, and generated management routines; not a complete hosted control plane. |
| IntelliJ plugin | Separate experimental build targeting IntelliJ 2024.1; not a published or supported Marketplace release. |

JDBC lowering is implemented, but not every JDBC API or Java construct is
supported. In particular, MySQL dynamic SQL is unsuitable inside stored
functions/triggers; prefer supported procedure shapes. Read the
[JDBC input guide](docs/transpilable-jdbc-subset.md) and
[developer guide](docs/developer-guide.md) before adopting a workflow.

The standalone DSL's `DSL.using(...)` runtime context and `render()` API should
not be assumed to be transpiler syntax. Use the compiler-supported static DSL
surface inside transpiled methods.

## Build and try it

Install JDK 21 and set `JAVA_HOME`. Clone both repositories side by side:

```bash
git clone https://github.com/rbilleci/titan-dsl.git
git clone https://github.com/rbilleci/titan.git
cd titan
./gradlew build
./gradlew -p examples/quickstart build
./gradlew -p examples/quickstart build -PtargetDialect=mysql
```

Use `gradlew.bat` on Windows. The first build downloads Gradle and dependencies.
The default build and quickstart use no Docker or live database, and require no
pre-existing Maven-local publication. If the DSL checkout is elsewhere, pass
`-PtitanDslDir=/path/to/titan-dsl` to the core build.

For Docker-backed deployment, binding, equivalence, and management checks:

```bash
./gradlew integrationTest
```

GitHub Actions CI workflows are intentionally absent. Contributors must run and
report local verification. See [testing](docs/testing.md).

### Database targets

The live container suites target **PostgreSQL 16** and **MySQL 8.4**. Those are the
recommended evaluation targets for this source revision.

The compiler models feature floors such as PostgreSQL 15 for generated
security-invoker views and MySQL 8.0.31 for INTERSECT/EXCEPT. Older versions meeting
a feature floor are not thereby tested or certified; verify your exact server,
extensions, SQL mode, permissions, and generated routines before deployment.
PostgreSQL scheduled jobs require pg_cron; MySQL events require the relevant
scheduler configuration and privileges.

## Use the plugin in your application

With `my-app/`, `titan/`, and `titan-dsl/` beside one another, put this in
`my-app/settings.gradle.kts`:

```kotlin
pluginManagement { includeBuild("../titan") }
rootProject.name = "my-app"
includeBuild("../titan")
```

Then use the [complete example build](examples/quickstart/build.gradle.kts) as a
starting point. Apply `io.titan.gradle`, depend on
`io.titan:titan-dsl:0.1.0`, and configure a schema source and target. The full
plugin applies the generation-only `io.titan.codegen` plugin itself; do not
configure two independent generation pipelines.

```bash
../titan/gradlew titanPackage
../titan/gradlew titanJdbcCompatReport titanPermissiveScopesReport
```

These source composites resolve local Titan and DSL code. Registry coordinates
alone are not an installation route until artifacts are actually published.
Local Maven publication is available for development, but consumers must publish
both repositories' required artifacts and must not rely on stale local versions.

## What the pipeline produces

```text
schema (JDBC or DDL) → Java catalog
annotated Java + catalog → generated SQL → migration package
                                           ↓
                                  optional install verification
```

| Task | Result / side effects |
| --- | --- |
| `titanIntrospect`, `titanGenerate` | Schema snapshot and Java catalog; database access or scratch containers depend on source mode. |
| `titanTranspile` | SQL under `build/generated/sql/titan/<dialect>/`. |
| `titanPackage` | Migration bundles, object/install metadata, checksums, and rollback scripts under `build/titan/migrations/`. Does not deploy. |
| `titanVerifyInstall` | **Executes DDL** in scratch containers by default, or in the explicitly configured verification database. |
| `titanJdbcCompatReport` | Static compatibility report; not a substitute for successful transpilation and database testing. |
| `titanPermissiveScopesReport` | Reports effective SQL-safety relaxations, including build/class-level settings. |

Review generated SQL and rollback scripts. Packaging does not automatically
provision your application's schema, enforce authorization, or make deployment
reversible without data loss. Never use a production database as a casual
verification target.

## Repository map

- `titan-transpiler`: parsing, validation, typed intermediate representation, passes, emitters.
- `titan-gradle-plugin`: build tasks, packaging, and install verification.
- `titan-runtime-jdbc`: application-side execution and testing helpers.
- `titan-management` / `titan-management-routines`: experimental control-plane storage and generated SQL.
- `titan-intellij-plugin`: standalone IDE experiment.
- `examples/quickstart`: runnable source consumer.

Schema introspection, catalog generation, the generation-only plugin, and the
DSL are maintained in [Titan DSL](https://github.com/rbilleci/titan-dsl), not
duplicated in this repository.

## Documentation and contributing

Start with the [developer guide](docs/developer-guide.md). Further reading:

- [Architecture](docs/design-document.md) and [adding a dialect](docs/adding-a-dialect.md)
- [Schema sourcing](docs/schema-sourcing-guide.md) and [JDBC input](docs/transpilable-jdbc-subset.md)
- [Application integration](docs/adoption-runtime-integration-guide.md)
- [Testing](docs/testing.md), [deployment readiness](docs/production-readiness-plan.md), and [release checklist](docs/releasing.md)
- [Contributing](CONTRIBUTING.md), [security reporting](SECURITY.md), and [third-party notices](THIRD_PARTY_NOTICES.md)

Titan-owned files are GPL-3.0-only; see [LICENSE](LICENSE). The
[preparation plan](docs/public-release-plan.md) records verification and remaining
public-visibility gates.
