# Standard JDBC input

Titan implements a supported subset of ordinary `java.sql` input. It does not
require converting every accepted method into DSL calls, and JDBC lowering is
not merely a planned feature.

Start with the [runnable example](../examples/quickstart/README.md), then use
`titanJdbcCompatReport`, transpilation, and live-database tests on your own method.
The static report measures recognizable shapes; it is not a deployment guarantee.

## One native dialect per JDBC pipeline

Embedded SQL already has a dialect. Configure one source/target database for a
JDBC method and keep the SQL valid for that database. The compiler rewrites
parameter bindings and emits the enclosing routine; it does not translate
arbitrary PostgreSQL SQL into MySQL SQL or vice versa.

The example's update happens to be valid on both databases and is built in
separate target-specific invocations. That is not a portability claim for
general JDBC input.

## Useful supported shapes

| Shape | Guidance / evidence |
| --- | --- |
| Connection/DataSource infrastructure | Recognized infrastructure parameters/acquisition are elided from routine signatures. See `JdbcEndToEndTest`. |
| Prepared statements and ordered setters | Use constant SQL and explicit bind positions/types. Supported shapes preserve value binding. |
| Single-row reads | Read into supported locals before branching or returning. A direct getter in a return expression is not interchangeable with a recognized INTO target. |
| Cursor loops | Recognized `while (rs.next())` loops lower with their supported body/control flow. A lexical inner cursor with constant bound SQL lowers as a nested cursor block; each cursor still has its own supported getter/control-flow constraints. |
| Updates/inserts/deletes | Recognized `executeUpdate` paths lower into the native routine. |
| Generated keys | Supported shapes require schema/key metadata and target-specific handling; see `JdbcGeneratedKeyResolverTest` and deployability tests. |
| Guarded predicates, collection IN forms, UUIDs | Specific recognized patterns have dedicated tests. Do not generalize support to every collection/SQL-building API. |
| Java branching, loops, exceptions, arithmetic | Limited by the same supported Java/semantic rules as other compiler input. |

Primary implementation evidence is under
`titan-transpiler/src/test/java/io/titan/transpiler/jdbc/` and
`titan-transpiler/src/test/java/io/titan/transpiler/tir/`:
`JdbcLoweringTest`, `JdbcEndToEndTest`, `JdbcGoldenTest`,
`JdbcEmitterDeployabilityIT`, and the dedicated dynamic/collection/UUID tests.

## Safety

Strict mode is the default. Keep values in bind parameters, not identifier or
SQL-fragment concatenations. Some recognized dynamic placeholder/predicate
patterns remain bindable; arbitrary runtime SQL text is not equivalent to them.

`@SqlSafety(PERMISSIVE)` and the build-level `sqlSafety` option allow explicit
escape paths. They are not sanitizers. Inspect
`titanPermissiveScopesReport`, especially when a class/build setting relaxes
methods that have no local annotation. The older `strictMode` property is a no-op.

## Important limitations

- Not every JDBC setter/getter, overload, cursor lifetime, result shape, transaction
  operation, batch, or generated-key combination is supported.
- Connection pooling, remote calls, framework services, and arbitrary object
  materialization do not move into the database.
- Preserve and test null/default-value semantics, exceptions, numeric bounds,
  precision, and transactional effects.
- MySQL forbids dynamic PREPARE/EXECUTE inside stored functions and triggers.
  A Java method compiling or a byte-golden passing does not make that combination
  deployable. Use supported procedure shapes or reviewed static SQL.
- Do not assume unsupported raw/vendor SQL will be caught before deployment.
- Compare the generated routine's signature and results with the Java call path;
  the application must explicitly call the installed SQL routine.

See the [developer guide](developer-guide.md) and
[deployment checklist](production-readiness-plan.md).
