# Schema sources

Schema introspection and catalog generation are implemented in
[Titan DSL](https://github.com/rbilleci/titan-dsl). The full Titan Gradle plugin
composes its generation-only plugin and retains `titan.database` /
`titan.catalog` settings.

## Choose the source

| Source | Use when | Requirements |
| --- | --- | --- |
| Live JDBC | You need metadata from a reproducible database/schema. | JDBC URL, dedicated credentials, matching driver on `titanJdbc`. |
| DDL, container mode (default) | You have vendor DDL/migrations and need database interpretation. | Docker, database image, matching JDBC driver. |
| DDL, parser mode | A small fixture fits the bounded supported grammar. | No Docker; unsupported constructs fail rather than being a full database parser. |

Use the database or authoritative migrations as the source of truth. Do not
silently discard constraints, types, views, or clauses to make a reduced fixture
appear representative of production.

For a live source, supply connection values through environment-backed providers:

```kotlin
titan {
    database {
        dialect.set("postgresql")
        schemas.set(listOf("app"))
        jdbcUrl.set(providers.environmentVariable("TITAN_DB_URL"))
        username.set(providers.environmentVariable("TITAN_DB_USER"))
        password.set(providers.environmentVariable("TITAN_DB_PASSWORD"))
    }
}
```

For DDL, replace the connection settings with `ddlDir.set(...)`; explicitly set
`ddlMode.set("parser")` only for the bounded parser path. See the
[example configuration](../examples/quickstart/build.gradle.kts).

## Generated output is owned output

Use a dedicated generated-source directory. The generator tracks its owned
files, removes obsolete owned sources, and rejects unsafe collisions/symlink
paths. Do not edit generated sources or mix hand-written files into their paths.

Regenerate after schema changes and compile affected query/routine code. This
can expose removed/renamed fields and incompatible type changes; it does not
detect live schema drift if you keep using a stale catalog.

Treat schema snapshots as potentially sensitive metadata. Generated catalogs
are not an automatic deployment plan for application tables: schema migrations
and routine installation have separate responsibilities.
