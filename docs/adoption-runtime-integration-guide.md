# Integrate a generated routine into an application

Start with one bounded method/workflow and keep the application-facing contract
unchanged. A source build does not automatically replace Java methods at runtime.

1. Capture the existing behavior in tests, including nulls, exceptions, row counts,
   numeric bounds, and transaction behavior.
2. Compile a supported routine against a reproducible schema source.
3. Review SQL/package metadata and install it into a disposable database.
4. Add an explicit application adapter that calls the SQL routine through your
   existing JDBC connection/pool.
5. Compare Java and SQL results and side effects, then measure realistic workloads.
6. Deploy through the application's migration/release process with an operational
   rollback plan and backups.

The application still owns HTTP/API contracts, authorization, transaction
boundaries, result mapping, exception translation, and connection lifecycle.
Keep the original path available during an evaluation where practical.

## Runtime library

`titan-runtime-jdbc` supplies execution helpers and Java/SQL/equivalence test
infrastructure. It currently declares driver, JUnit, and Testcontainers runtime
dependencies; inspect its POM before adopting it in a production service.
Calling the generated SQL through existing application JDBC is also valid.

Do not assume equivalence helpers automatically instrument arbitrary application
code or prove business equivalence. Construct an explicit harness around the
method, inputs, state, and side effects you need to compare.

## Operational boundaries

- Generated procedures/functions may have signatures different from the original
  Java method, notably elided Connection/DataSource infrastructure.
- A migration package does not provision every application table or extension.
- Different SQL modes, collations, time zones, privilege contexts, and isolation
  levels can change behavior.
- Generated rollback scripts can drop data-bearing objects. Review them and
  manage backups, scheduler entries, and application rollout separately.
- Benchmark before broad adoption; database-side execution is a tradeoff, not an
  unconditional performance improvement.

See the [developer guide](developer-guide.md) and
[deployment readiness checklist](production-readiness-plan.md).
