# Titan quickstart

This example compiles ordinary annotated Java into database routines. It includes
a parameterized JDBC update, a scalar Java function, DDL input, generated catalog
classes, and deterministic migration packaging.

Prerequisites: Java 21, this Titan checkout, and the separate Titan DSL checkout
beside it. No installed Titan artifacts, live database, Docker, or credentials are
required for the following commands, run from the Titan repository root:

```bash
./gradlew -p examples/quickstart build
./gradlew -p examples/quickstart build -PtargetDialect=mysql
```

The default target is PostgreSQL. Each invocation uses one native target for the
JDBC input; Titan does not translate arbitrary embedded SQL between dialects.
The small update in this example happens to be valid in both databases.

Inspect:

- `build/generated/sources/titan/`: schema-derived Java catalog.
- `build/generated/sql/titan/<dialect>/`: generated routines.
- `build/titan/migrations/`: SQL bundles, artifact metadata, install plan, and
  rollback scripts.
- `build/reports/titan/`: JDBC compatibility and effective-permissive-scope reports.

The paths above are relative to this example. `verifyExample`, wired into `build`,
checks that the expected catalog, routines, package, and reports exist. It is a
generation/packaging smoke check, not a database execution test.

The supplied DDL is schema input; packaging routines does not provision the
application's tables. Apply your schema/migrations to a disposable target before
executing `creditAccount`. The Java `Connection` is omitted from the SQL routine
signature, while the account ID and amount become routine parameters.

`titanVerifyInstall` is a separate, mutating database verification task. Its
default uses disposable Docker containers; a configured verification JDBC URL
instead receives DDL changes. Do not point it at production. See the
[developer guide](../../docs/developer-guide.md) for deployment and safety limits.
