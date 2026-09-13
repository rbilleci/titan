# Titan

[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)

**Write database routines in Java. Ship reviewable SQL.**

Titan compiles supported Java methods into PostgreSQL and MySQL stored procedures,
functions, triggers, and scheduled jobs. Keep source code in your Java project,
inspect the generated SQL, and package it with your database migrations.

Use schema-generated Titan DSL types with Java business rules, or bring supported
JDBC code. Titan is a compiler for a defined subset—not a way to run arbitrary
Java inside a database, an ORM replacement, or an automatic application migration.

The [complete quickstart](examples/quickstart/README.md) includes the schema,
generated catalog, build configuration, and tests for the DSL-led inventory
workflow and tiered usage pricing shown below.

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

## Business logic, from Java to SQL

The useful unit is a workflow: read state, apply rules, and write the result—not
just a SQL statement wrapped in Java. These two examples are part of the runnable
[quickstart source](examples/quickstart/src/main/java/example/BusinessRoutines.java).
The SQL below is actual PostgreSQL output, not a hand-written translation.
The same examples are separately transpiled and executed on MySQL 8.4 as well.

### Reserve inventory: lock, validate, decrement, record

An order reservation must reject non-positive quantities, detect an unknown SKU,
and refuse insufficient stock. On success, it decrements inventory and records
the reservation. The locking read prevents a competing reservation following
the same protocol from making its decision on the same unlocked stock count.

The table and column objects come from the schema generator—not hand-maintained
strings or duplicate model classes:

```java
import static generated.catalog.app.tables.Inventory.INVENTORY;
import static generated.catalog.app.tables.Reservations.RESERVATIONS;
import static titan.dsl.DSL.insertInto;
import static titan.dsl.DSL.select;
import static titan.dsl.DSL.update;
```

Use the static, compiler-supported DSL inside the annotated routine:

<!-- example:inventory:java -->
```java
@StoredProcedure
public static void reserveStock(long orderId, long sku, int quantity) throws SQLException {
    if (quantity <= 0) {
        throw new SQLException("quantity must be positive");
    }
    Integer available = select(INVENTORY.AVAILABLE)
            .from(INVENTORY)
            .where(INVENTORY.SKU.eq(sku))
            .forUpdate()
            .fetchScalar();
    if (available == null) {
        throw new SQLException("unknown SKU");
    }
    if (available < quantity) {
        throw new SQLException("insufficient stock");
    }
    update(INVENTORY)
            .set(INVENTORY.AVAILABLE, INVENTORY.AVAILABLE.subtract(quantity))
            .where(INVENTORY.SKU.eq(sku))
            .execute();
    insertInto(RESERVATIONS)
            .set(RESERVATIONS.ORDER_ID, orderId)
            .set(RESERVATIONS.SKU, sku)
            .set(RESERVATIONS.QUANTITY, quantity)
            .execute();
}
```

Titan lowers the DSL directly to structured SQL: `SELECT … INTO … FOR UPDATE`,
Java guards become SQL branches and exceptions, and typed mutations become
`UPDATE` and `INSERT`. There are no embedded SQL strings or JDBC objects in this
Java entry point:

<!-- example:inventory:postgresql -->
```sql
CREATE OR REPLACE PROCEDURE "app"."reserve_stock"(p_order_id BIGINT, p_sku BIGINT, p_quantity INTEGER)
LANGUAGE plpgsql
SECURITY INVOKER
AS $$
DECLARE
    v_available INTEGER;
BEGIN
    IF COALESCE((p_quantity <= 0), FALSE) THEN
        RAISE EXCEPTION USING ERRCODE = 'HY000', MESSAGE = 'quantity must be positive';
    END IF;
    SELECT "inventory"."available" INTO v_available FROM "app"."inventory" WHERE COALESCE(("inventory"."sku" = p_sku), FALSE) LIMIT 1 FOR UPDATE;
    IF v_available IS NULL THEN
        RAISE EXCEPTION USING ERRCODE = 'HY000', MESSAGE = 'unknown SKU';
    END IF;
    IF COALESCE((v_available < p_quantity), FALSE) THEN
        RAISE EXCEPTION USING ERRCODE = 'HY000', MESSAGE = 'insufficient stock';
    END IF;
    UPDATE "app"."inventory" SET "available" = ("inventory"."available" - p_quantity) WHERE COALESCE(("inventory"."sku" = p_sku), FALSE);
    INSERT INTO "app"."reservations" ("order_id", "sku", "quantity") VALUES (p_order_id, p_sku, p_quantity);
END;
$$;
```

The [example DDL](examples/quickstart/src/main/resources/db/schema/inventory.sql)
makes `sku` a primary key, stock non-null, and `(order_id, sku)` unique for
reservations. A missing scalar row lowers to SQL NULL, so the Java local is
`Integer` and is checked before use; it cannot be confused with a stored NULL in
this schema. This is not an idempotent retry API: repeating a reservation raises
a duplicate-key error.

These DSL terminals describe compiler input; calling this method directly on the
JVM is not a database execution path. Your application invokes the installed SQL
procedure through its connection. The standalone `DSL.using(...).render()` API is
a separate runtime SQL-construction surface.

**The caller owns the transaction.** Disable autocommit, call the procedure, then
commit on success or roll back on any error. This is necessary to retain the row
lock and treat both writes as one unit, particularly when the reservation insert
fails after stock was decremented. Titan does not insert transaction management,
authorization, or a complete order-processing system for you. Use transactional
tables on MySQL.

<details>
<summary>JDBC alternative: the same workflow without rewriting existing SQL</summary>

Supported existing JDBC code can express the same business rules. The connection
and result-set plumbing disappear during transpilation; SQL binds become routine
parameters. This version produces a separate `reserve_stock_jdbc` procedure and
uses native SQL for the selected target.

<!-- example:inventory-jdbc:java -->
```java
@StoredProcedure
public static void reserveStockJdbc(Connection connection, long orderId, long sku, int quantity)
        throws SQLException {
    if (quantity <= 0) {
        throw new SQLException("quantity must be positive");
    }
    int available;
    try (PreparedStatement read = connection.prepareStatement(
            "SELECT available FROM app.inventory WHERE sku = ? FOR UPDATE")) {
        read.setLong(1, sku);
        try (ResultSet rows = read.executeQuery()) {
            if (!rows.next()) {
                throw new SQLException("unknown SKU");
            }
            available = rows.getInt("available");
        }
    }
    if (available < quantity) {
        throw new SQLException("insufficient stock");
    }
    try (PreparedStatement update = connection.prepareStatement(
            "UPDATE app.inventory SET available = available - ? WHERE sku = ?")) {
        update.setInt(1, quantity);
        update.setLong(2, sku);
        update.executeUpdate();
    }
    try (PreparedStatement insert = connection.prepareStatement(
            "INSERT INTO app.reservations (order_id, sku, quantity) VALUES (?, ?, ?)")) {
        insert.setLong(1, orderId);
        insert.setLong(2, sku);
        insert.setInt(3, quantity);
        insert.executeUpdate();
    }
}
```

Both implementations run through the same success, validation, and rollback tests
on PostgreSQL and MySQL. JDBC is an alternative input style, not a prerequisite
for SQL lowering.

</details>

### Price usage: graduated tiers, partner discount, minimum charge

A sample billing policy charges the first 100 units at 5 cents, the next 900 at
3 cents, and additional units at 2 cents. Partners pay 90% of that subtotal,
rounded down to whole cents. Nonzero usage has a 200-cent minimum; zero usage is
free. The upper bound keeps this example's integer arithmetic within range.

Keep the rule and its helper in Java:

<!-- example:pricing:java -->
```java
@StoredFunction
public static int monthlyChargeCents(int units, boolean partner) {
    if (units < 0 || units > 1000000) {
        throw new IllegalArgumentException("units must be between 0 and 1000000");
    }
    int remaining = units;
    int cents = 0;
    if (remaining > 1000) {
        cents += (remaining - 1000) * 2;
        remaining = 1000;
    }
    if (remaining > 100) {
        cents += (remaining - 100) * 3;
        remaining = 100;
    }
    cents += remaining * 5;
    if (partner) {
        cents = partnerPrice(cents);
    }
    if (units > 0 && cents < 200) {
        return 200;
    }
    return cents;
}

private static int partnerPrice(int cents) {
    return cents * 9 / 10;
}
```

For 1,200 units, the result is `100 × 5 + 900 × 3 + 200 × 2 = 3,600` cents,
or `3,240` for a partner. One unit still costs the 200-cent minimum.

<details>
<summary>See the generated PostgreSQL function</summary>

<!-- example:pricing:postgresql -->
```sql
CREATE OR REPLACE FUNCTION "app"."monthly_charge_cents"(p_units INTEGER, p_partner BOOLEAN)
RETURNS INTEGER
LANGUAGE plpgsql
SECURITY INVOKER
AS $$
DECLARE
    v_remaining INTEGER;
    v_cents INTEGER;
BEGIN
    IF COALESCE(((p_units < 0) OR (p_units > 1000000)), FALSE) THEN
        RAISE EXCEPTION USING ERRCODE = '22023', MESSAGE = 'units must be between 0 and 1000000';
    END IF;
    v_remaining := p_units;
    v_cents := 0;
    IF (v_remaining > 1000) THEN
        v_cents := (v_cents + ((v_remaining - 1000) * 2));
        v_remaining := 1000;
    END IF;
    IF (v_remaining > 100) THEN
        v_cents := (v_cents + ((v_remaining - 100) * 3));
        v_remaining := 100;
    END IF;
    v_cents := (v_cents + (v_remaining * 5));
    IF COALESCE(p_partner, FALSE) THEN
        v_cents := __titan_internal_business_routines_partner_price_2ecc7d31(v_cents);
    END IF;
    IF COALESCE(((p_units > 0) AND (v_cents < 200)), FALSE) THEN
        RETURN 200;
    END IF;
    RETURN v_cents;
END;
$$;
```

</details>

The branches, local variables, early return, and helper call become SQL
control flow. Titan emits the private helper as an internal SQL function and
packages it with the entry point. PostgreSQL uses integer division here; the
MySQL target uses Titan's integer-division helper to preserve truncation.
Install the generated runtime bundle before the routine bundle; copying only the
entry-point function omits its dependencies. On MySQL, select the application's
database when installing the unqualified runtime helpers and provision the
`titan_runtime` schema required by the runtime migration.

For this PostgreSQL example, include the application-owned `app` schema in the
trusted search path so generated helper calls resolve:

```sql
SET search_path TO app, public;
SELECT app.monthly_charge_cents(1200, false); -- 3600
SELECT app.monthly_charge_cents(1200, true);  -- 3240
SELECT app.monthly_charge_cents(1, true);     -- 200
```

### Run and verify these examples

```bash
./gradlew -p examples/quickstart build
./gradlew -p examples/quickstart build -PtargetDialect=mysql
# Docker: deploy the actual example source and exercise success/failure paths.
./gradlew :titan-transpiler:integrationTest --tests '*ReadmeBusinessExamplesIT'
```

The database tests regenerate the catalog from DDL and run **both DSL and JDBC**
reservations through identical cases: success, invalid quantities, unknown
SKUs, insufficient stock, exact-stock exhaustion, and rollback after a duplicate
reservation. They also check pricing tier
boundaries, discounts, minimums, and out-of-range input. They are not a concurrency
stress test or production certification. Apply the application's DDL before
calling its routines; generation alone does not create your tables.

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
