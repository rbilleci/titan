# Titan quickstart

This example compiles annotated Java and schema-generated Titan DSL expressions
into database routines. It includes DDL input, generated catalog classes, JDBC
alternatives, scalar functions, and deterministic migration packaging. The root [README](../../README.md)
walks through two larger examples in `BusinessRoutines.java` with actual SQL:

- DSL inventory reservation: typed locking read, input/stock checks, decrement,
  and insert, using generated `Inventory` and `Reservations` descriptors.
- JDBC reservation alternative: the same workflow using prepared statements.
- Usage pricing: graduated tiers, partner-discount helper, and a minimum charge.

Prerequisites: Java 21, this Titan checkout, and the separate Titan DSL checkout
beside it. No installed Titan artifacts, live database, Docker, or credentials are
required for the following commands, run from the Titan repository root:

```bash
./gradlew -p examples/quickstart build
./gradlew -p examples/quickstart build -PtargetDialect=mysql
```

The default target is PostgreSQL. Each invocation uses one native target for the
JDBC input; Titan does not translate arbitrary embedded SQL between dialects.
The embedded SQL in these examples happens to be valid in both databases.

Inspect:

- `build/generated/sources/titan/`: schema-derived Java catalog.
- `build/generated/sql/titan/<dialect>/`: generated routines.
- `build/titan/migrations/`: SQL bundles, artifact metadata, install plan, and
  rollback scripts.
- `build/reports/titan/`: JDBC compatibility and effective-permissive-scope reports.

The paths above are relative to this example. `verifyExample`, wired into `build`,
checks that the expected catalog, routines, package, and reports exist. It is a
generation/packaging smoke check, not a database execution test.

To deploy and execute the business examples on disposable PostgreSQL and MySQL
databases, including their rejection and transaction-rollback paths, run from
the Titan repository root with Docker available:

```bash
./gradlew :titan-transpiler:integrationTest --tests '*ReadmeBusinessExamplesIT'
```

The supplied DDL is schema input; packaging routines does not provision the
application's tables. Apply your schema/migrations to a disposable target before
executing `creditAccount`. The Java `Connection` is omitted from the SQL routine
signature, while the account ID and amount become routine parameters.

`reserveStock` and `reserveStockJdbc` require an explicit caller-owned transaction: disable autocommit,
commit on success, and roll back on any error. Use transactional MySQL tables.
The reservation key rejects duplicates; it does not make retries idempotent.
Both versions run through identical database tests, including a missing SKU,
negative/zero quantities, insufficient stock, exact-stock exhaustion, and rollback
after a duplicate reservation. Tests regenerate the catalog from DDL and do not
depend on an earlier quickstart build.

The DSL version uses the compiler-supported static entry points and `fetchScalar()`.
It describes a routine for transpilation, not a JDBC call when invoked directly
in a JVM. Call the installed procedure through the application's connection.
Its nullable `Integer` local detects a missing row before comparison; the schema
does not permit a stored NULL stock value. The `DSL.using(...).render()` runtime
API is a separate SQL-construction surface.

The pricing helper uses integer cents and truncates the partner discount before
applying the minimum. Install runtime dependencies before routines; on MySQL the
integer-division helper must be installed into `app`, with `titan_runtime` also
provisioned for the runtime migration. PostgreSQL helper calls in this example
require the trusted application schema in the search path (`app, public`).

`titanVerifyInstall` is a separate, mutating database verification task. Its
default uses disposable Docker containers; a configured verification JDBC URL
instead receives DDL changes. Do not point it at production. See the
[developer guide](../../docs/developer-guide.md) for deployment and safety limits.
