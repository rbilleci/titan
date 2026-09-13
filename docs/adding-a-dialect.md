# Adding a Dialect to the Titan Transpiler

This contributor checklist describes the provider-based extension surface. A
skeletal registration spike is not a supported database target: a real addition
also needs complete emission, capabilities, runtime helpers, and live verification.
Check the current interfaces alongside the guidance below when implementing one.

All paths below are relative to `titan-transpiler/src/main/java/io/titan/transpiler/` unless
stated otherwise.

---

## 0. The two registration points (the only existing files you edit)

Everything else in this guide is a **new file**. A new dialect edits exactly two existing
lines of production code:

| # | File | Edit |
|---|------|------|
| 1 | `tir/DialectId.java` | Add the enum constant, with its transpile-target aliases: `H2("h2")`. The first alias is canonical and appears in "Use one of: ..." diagnostics. `DialectId.parse(...)` and `SqlAnnotationProcessor`'s `@SQL`-dialect validation derive from the constants automatically. |
| 2 | `tir/DialectProviders.java` | Register the provider in `defaultProviders()`: add `DialectProvider h2 = new H2DialectProvider();` and the `h2.id(), h2` map entry. |

There is deliberately **no** per-dialect switch left anywhere in the pipeline:

- `TranspilationPipeline.parseDialect` resolves aliases via `DialectId.parse`.
- `DialectCapabilities.forDialect` resolves through the `DialectProviders` registry — each
  provider owns its own capability descriptor.
- Routine naming (identifier length limit, overload mangling) reads
  `DialectCapabilities.NamingRules`.
- Lossy-TIMESTAMPTZ warnings read `DialectCapabilities.TimestampTzRangeCaveat`.
- Lossy-cron warnings come from the emitter hook `SqlEmitter.scheduledJobCronApproximation`.
- `@SQL` raw-block routing matches `DialectId.name()` against the annotation's dialect string.
- `titan-gradle-plugin`'s `TitanPackageTask.runtimeMigrationSqlForDialect` resolves through
  `DialectId.parse` + the provider registry.

If you find yourself adding a `case` or an `if (dialect == ...)` outside a provider, an
emitter, a `TypeMapper`, or a `DialectCapabilities` descriptor, you are reintroducing the
problem this phase removed — put the behavior on the provider surface instead.

## 1. `DialectId` constant (registration point 1)

`tir/DialectId.java` — one constant declaration carrying every accepted transpile-target
spelling, e.g. `POSTGRESQL("postgresql", "postgres", "pg")`. Aliases are matched
case-insensitively; the first one is the canonical spelling used in diagnostics and should be
the directory name users put in `transpile targets`.

## 2. `DialectProvider` implementation (new file)

`tir/H2DialectProvider.java` implementing `DialectProvider` — six methods, all required:

| Method | What it must do |
|--------|-----------------|
| `id()` | Return the new `DialectId` constant. |
| `capabilities()` | Return the dialect's canonical `DialectCapabilities` descriptor (step 3). Each provider owns its descriptor; there is no central registry switch. |
| `emitter()` | Return a `SqlEmitter` adapter that constructs a **fresh concrete emitter per call** (`new H2Emitter().emitProcedure(...)`). Emitters are stateful (`CodeBuffer`, routine parameters, name allocator), so the provider-level `SqlEmitter` must never reuse an instance across emissions. Follow the anonymous-adapter pattern in `PostgreSqlDialectProvider`/`MySqlDialectProvider`. |
| `typeMapper()` | Return the dialect's `TypeMapper` (step 4); a stateless singleton is fine. |
| `runtimeStrategy()` | Return the dialect's `RuntimeStrategy` (step 6). |
| `migrationStrategy()` | Return the dialect's `MigrationStrategy` (step 7). |

If the dialect's scheduler cannot represent arbitrary cron expressions, also override the
emitter-adapter hook
`Optional<SqlEmitter.CronApproximation> scheduledJobCronApproximation(String cron)` — return
the warning message (naming the original cron and the emitted approximation) plus a rewrite
suggestion. The pipeline turns it into a positioned `TITAN-W005`. Dialects that schedule the
exact cron (PostgreSQL/pg_cron) keep the empty default.

## 3. `DialectCapabilities` descriptor (lives in the provider, no new file)

Construct one `DialectCapabilities` record (usually a `private static final` in the provider):

| Component | What it must declare |
|-----------|----------------------|
| `dialect` | The `DialectId` constant. |
| `displayName` | Human-readable name used in capability-rejection diagnostics ("MySQL"). |
| `updateDeleteReturning` | `Capability` cell: can UPDATE/DELETE return rows? `unsupported(...)` cells **must** carry a rewrite suggestion (enforced by the record's invariant). |
| `fullOuterJoin` | `Capability` cell for FULL OUTER JOIN. Must agree with the emitter's `fullOuterJoinKeyword()` hook (capability = compile-time positioned rejection; emitter throw = emit-time defense-in-depth). |
| `stringFormat` | `Capability` cell for `String.format` lowering. |
| `intersectExcept` | `Capability` cell for INTERSECT/EXCEPT; use `supportedSince("x.y.z")` for version-gated support (becomes a W005 naming the version floor). |
| `namingRules` | `NamingRules(identifierMaxLength, supportsRoutineOverloading)`. The pipeline truncates generated internal-helper names (hash-suffix-preservingly) to `identifierMaxLength` (PostgreSQL 63 = NAMEDATALEN-1, MySQL 64) and parameter-type-mangles SQL names of overloaded Java methods when `supportsRoutineOverloading` is false. TG-BLK-011: every other generated name family (record/enum types and member accessors, public routine names, trigger `_fn`/`_trg` and event-name decorations) is length-limited by `SqlNames.fitWithinDialectLimits`, which uses the **minimum** `identifierMaxLength` across all registered dialects (those names are baked into dialect-neutral TIR before per-target emission) — registering a dialect with a ceiling below 63 deterministically re-shortens long generated names for every target. |
| `timestampTzRangeCaveat` | `TimestampTzRangeCaveat(message, suggestion)` if the dialect's Instant/ZonedDateTime/OffsetDateTime mapping narrows the representable range (MySQL TIMESTAMP(6) → 1970..2038), else `null`. Drives the per-signature/per-record W005 warnings. |

`FeatureValidator` consumes the cells at compile time and turns unsupported construct/dialect
combinations into positioned `TITAN-E001` diagnostics — keep the descriptor honest or users
get emit-time failures with no source location.

## 4. `TypeMapper` (new file)

`tir/H2TypeMapper.java` implementing `TypeMapper.toSqlType(TirType)`. It must map every
`StandardTirTypes` member (see `PostgreSqlTypeMapper` for the canonical exhaustive switch):

`TIntType`, `TBigintType`, `TBooleanType`, `TTextType`, `TNumericType(precision, scale)`,
`TDateType`, `TTimeType`, `TTimestampType`, `TTimestampTzType`, `TDurationType`,
`TPeriodType`, `TArrayType` (element-type recursive; only if `RuntimeStrategy.supportsNativeArrays()`),
`TJsonType`, `TCompositeType`, `TEnumType`, `TRecordType` (qualified `__record_<snake of the
source-local qualified record name>` backing-type name — build it via `SqlNames.recordSqlBaseName`,
which also documents the B-2/TG-BLK-005 collision-free `__` member join), `TVoidType`.

Preserve fractional-seconds precision where the dialect needs it (E-13: MySQL maps timestamp
types to `DATETIME(6)`/`TIMESTAMP(6)` so Java microseconds survive).

## 5. `AbstractSqlEmitter` subclass (new file — the big one)

`tir/H2Emitter.java extends AbstractSqlEmitter`. The base class implements the
dialect-neutral majority of `TirVisitor<String>` (29 of 51 visitor methods) plus shared
helpers (identifier validation, named-parameter rewriting, enum/record lookup scaffolding,
order-by/join/grouping rendering). A concrete subclass must implement **44 members**:

### 5a. Seven public abstract emit entry points

| Method | What it must do |
|--------|-----------------|
| `emitProcedure(schema, name, securityMode, body, routineParameters, observability, sensitiveColumnsAccessed)` | Full CREATE PROCEDURE artifact: header, parameter list, security mode, observability/telemetry scaffolding, body. |
| `emitFunction(schema, name, securityMode, returnType, body, routineParameters, observability, sensitiveColumnsAccessed)` | Full CREATE FUNCTION artifact including RETURNS clause. |
| `emitScheduledJob(schema, name, securityMode, scheduledJobSpec, body, observability, sensitiveColumnsAccessed)` | The routine plus the dialect's scheduling artifact (pg_cron `cron.schedule` vs MySQL `CREATE EVENT`); if the schedule is approximated, embed the approximation comment in the artifact (defense-in-depth alongside the `scheduledJobCronApproximation` warning). |
| `emitTrigger(schema, name, securityMode, triggerSpec, body)` | Trigger function/procedure plus CREATE TRIGGER wiring for `TriggerSpec(table, timing, events, forEach)`. |
| `emitView(schema, name, sqlBody)` | CREATE [OR REPLACE] VIEW for `@ViewDefinition` SQL bodies. |
| `emitEnumLookup(schema, enumLookupSpec)` | The `__enum_<snake>` lookup table (via `SqlNames.enumSqlBaseName`) + idempotent upsert seeding (ON CONFLICT vs ON DUPLICATE KEY); accessors join members with `SqlNames.MEMBER_JOIN` (`__`). |
| `emitRecordModel(schema, recordModelSpec)` | The `__record_<snake>` backing type/constructor (PostgreSQL `CREATE TYPE` composite vs MySQL JSON-constructor function); names come from `SqlNames.recordSqlBaseName`/`recordMemberName` (the `__` member join is the B-2 collision rule). |

### 5b. Fifteen protected abstract dialect hooks

| Hook | What it must do |
|------|-----------------|
| `sqlType(TirType)` | Map a TIR type to the dialect's SQL type name (delegate to your `TypeMapper`). |
| `escape(String)` | Escape a value for a single-quoted string literal (mind backslash semantics — MySQL escapes `\`, PostgreSQL standard-conforming strings do not). |
| `fullOuterJoinKeyword()` | Keyword for FULL OUTER JOIN; throw `unsupportedOnDialect(...)` if the dialect has none (and say so in the capability cell). |
| `elseIfKeyword()` | Else-if branch keyword: `ELSIF` (PL/pgSQL) vs `ELSEIF` (MySQL). |
| `characterLiteral(char)` | Render a char literal (PostgreSQL quotes it; MySQL uses `CHAR(n USING utf8mb4)`). |
| `parameterPlaceholder(int oneBasedIndex)` | Placeholder for the n-th named parameter in rewritten raw SQL: `$n` vs `?`. |
| `supportsDollarQuotedStrings()` | Whether `$tag$...$tag$` strings must be skipped when scanning raw SQL. |
| `staticResetCallSql(String key)` | Statement resetting one static-state key (`PERFORM titan_runtime.static_reset(...)` vs `SET @... = titan_rt_...`). |
| `staticSetCallSql(String keySql, String valueSql)` | Statement writing one static-state key/value pair. |
| `telemetrySuccessSql()` | Telemetry success UPDATE emitted before RETURN when observability is on. |
| `nullPointerSignalSql(String location)` | Statement raising the NullPointerException-parity error inside a NullAnalysisPass guard. |
| `reservedRoutineIdentifiers(boolean observability)` | Identifiers reserved by the emitter scaffolding (never usable for user variables). |
| `blockBody(Block)` | Render a nested block body (declaration hoisting differs per dialect). |
| `quoteIdentifier(String)` | Quote one schema-derived identifier (`"x"` vs `` `x` ``); must call `requireQuotableIdentifier` first. Read the base method's javadoc for the catalogued exclusions (locals, `NEW`/`OLD`, `*`, built-in function names, runtime-namespace references). |
| `truncateTowardZeroSql(String operandSql)` | Expression truncating a fractional value toward zero (Java cast semantics, F-9). |

### 5c. Twenty-two dialect-divergent `TirVisitor` methods

The base class leaves exactly these to the subclass (this list is what the compiler will
demand; verified against `PostgreSqlEmitter`):

`visitDeclareVariable`, `visitDeclareHandler`, `visitBlock`, `visitAssign`,
`visitWhileStatement`, `visitLoopStatement`, `visitForCursorStatement`,
`visitForEachStatement`, `visitForRangeStatement`, `visitBreakStatement`,
`visitContinueStatement`, `visitRaiseStatement`, `visitDebugPrintStatement`,
`visitTryCatchFinallyStatement`, `visitInsertSql`, `visitUpdateSql`, `visitDeleteSql`,
`visitRawSql`, `visitFunctionCallExpression`, `visitArrayConstructExpression`,
`visitArrayLengthExpression`, `visitArrayGetExpression`.

### 5d. Emitter conventions that are easy to miss

- **Unsupported constructs throw `unsupportedOnDialect(message)`** (a structured
  `TitanDiagnosticException` / `TITAN-E001`), never raw `IllegalArgumentException` — and the
  corresponding `DialectCapabilities` cell must be `unsupported(...)` so users get the
  *positioned* compile-time rejection first. `ExceptionDisciplineEnforcementTest` enforces the
  throw discipline by source scan; add the new emitter file to its `SCOPED_SOURCES` list.
- A skeletal emitter is legitimate scaffolding: implement the hooks you can, make the rest
  `throw unsupportedOnDialect(...)`, and mark every capability cell honestly. The pipeline,
  registry and tests all work with a partial dialect — that is exactly what the spike stub did.

## 6. `RuntimeStrategy` (new file)

`tir/H2RuntimeStrategy.java`:

| Method | What it must do |
|--------|-----------------|
| `runtimeNamespace()` | Namespace/prefix of the deployed runtime helpers (`titan_runtime` schema on PostgreSQL, `titan_rt_` function prefix on schema-less-function MySQL). |
| `supportsNativeArrays()` | Whether `TArrayType` maps to a native array type; if false, list/set/map operations route through the table-backed runtime helpers. |
| `runtimeMigrationSql()` | The complete runtime-helper migration script the packager deploys before any routine: Java arithmetic parity (`java_mod`, `java_int_div`, wraparound `java_int_add/sub/mul`), collection helpers (`list_*`, `map_*`, `set_*`, plus temp variants where needed), and static-state helpers (`static_get/set/reset`). `DialectProviderConformanceTest` asserts this corpus is present. |

## 7. `MigrationStrategy` (new file)

`tir/H2MigrationStrategy.java` — `routineDropStatement(schema, name, RoutineKind)`: the
idempotent drop (or create-or-replace) statement migrations use for rollback/replace
(`DROP PROCEDURE IF EXISTS s.n;` on MySQL; PostgreSQL relies on `CREATE OR REPLACE`).

## 8. Validation and test obligations

New-dialect work is not done when it compiles. The required legs, in dependency order:

1. **Registry conformance** — extend the parameterized `dialectProviders()` stream in
   `src/test/.../tir/DialectProviderConformanceTest.java` (provider exposes all strategy
   surfaces; emitter produces routine SQL; type mapper covers common TIR types;
   runtime-migration corpus present) and add dispatch assertions to
   `DialectProviderDispatchTest`.
2. **Capability tests** — `DialectCapabilitiesTest`: assert the new descriptor's cells,
   naming rules, and caveats; `FeatureValidatorTest`/`TranspilationPipelineValidationTest`:
   unsupported-construct rejection is a positioned E-001 and version-gated/lossy constructs
   warn (W005) when the new target is in the target list.
3. **Golden emitter tests** — `H2EmitterTest` mirroring `PostgreSqlEmitterTest`/
   `MySqlEmitterTest`: per-construct SQL assertions for every implemented hook, plus explicit
   `unsupportedOnDialect` assertions for the not-yet-implemented ones.
4. **Exception discipline** — add the emitter to
   `ExceptionDisciplineEnforcementTest.SCOPED_SOURCES`.
5. **Deployability IT variant** — extend `EmitterDeployabilityIT` (or add a sibling) with a
   Testcontainers container for the new database: every generated artifact must CREATE on a
   live server and execute on its happy path. Substring-asserting unit tests pass
   syntactically invalid SQL; this gate is what catches it.
6. **Differential leg** — a `*SqlPathIT` harness leg for the new dialect under
   `tir/generative/differential/` mirroring `SelectDifferentialPostgresSqlPathIT` (and the
   aggregation/join/subquery variants): generated query profiles execute on the live database
   and must be result-equal to the in-JVM reference evaluator. (The MySQL leg itself is plan
   Phase 5; a third dialect inherits the same obligation.)
7. **Quoting / runtime parity ITs** — `ReservedWordIdentifierIT` (reserved-word schema
   identifiers survive quoting end-to-end) and `RuntimeArithmeticEmulationIT` (Java arithmetic
   parity of the runtime helpers) get a leg for the new dialect.

## 9. Adjacent surfaces (outside titan-transpiler — when do they need touching?)

| Surface | Needed when | Notes |
|---------|-------------|-------|
| `titan.dsl.SqlDialect` (titan-dsl) | The new dialect should be usable in `@SQL(dialect = ...)` escape hatches or runtime DSL rendering (`render(SqlDialect)`). | One enum constant + DSL render-path support. The transpiler-side `@SQL` validation already accepts any `DialectId` name; the annotation's enum is the user-facing gate. |
| `titan-runtime-jdbc` | Applications execute against the new database through the Titan runtime. | Dialect resolution / generated-keys behavior. |
| `io.titan.introspect.Dialect` | Schema sourcing should *introspect* the new database. | Deliberately separate from `DialectId`: introspection is the schema *input* side; a transpile target does not require introspection support (schemas can be sourced from PostgreSQL/MySQL or DDL while targeting the new dialect). The switches in `SchemaIntrospector`/`DdlSchemaParser` stay. |
| `io.titan.management.ManagementTransactions` | Never for a new dialect per se. | The GAP-006 contract validation requires `postgresql`/`mysql` dialect-note keys as part of a fixed management contract; it is not part of the dialect pipeline. |
| Gradle plugin (`TitanPackageTask`) | Never. | Resolves targets through `DialectId.parse` + the provider registry since Phase 3.4. |

## 10. Residual inline-dialect inventory (audited 2026-06-11)

The Phase 3.4 audit removed every inline `DialectId` branch from the transpilation path:

- `TranspilationPipeline.toSqlRoutineName` PostgreSQL-63 truncation / MySQL overload-mangling
  branches → `DialectCapabilities.NamingRules` (note: MySQL helper names now also truncate at
  64 — the previous unbounded names exceeded MySQL's routine-name limit).
- `TranspilationPipeline` MySQL TIMESTAMPTZ-2038 signature/record warnings →
  `DialectCapabilities.TimestampTzRangeCaveat`.
- `TranspilationPipeline` MySQL cron-approximation warning →
  `SqlEmitter.scheduledJobCronApproximation` (provider emitter hook).
- `TranspilationPipeline.toRawSqlStatements` per-dialect switch → `DialectId.name()` match.
- `TranspilationPipeline.parseDialect` alias switch → `DialectId.parse` (aliases on the
  constants).
- `DialectCapabilities.forDialect` switch → provider-owned descriptors resolved through the
  registry.
- `SqlAnnotationProcessor.SUPPORTED_DIALECTS` hard-coded set → derived from
  `DialectId.values()`.
- `TitanPackageTask.runtimeMigrationSqlForDialect` (titan-gradle-plugin) duplicate alias
  switch (which silently returned `""` for unknown dialects) → `DialectId.parse`.

Documented as intentionally remaining (not part of the transpile-target abstraction):
the `io.titan.introspect.Dialect` switches and the management-contract dialect notes (see §9).
