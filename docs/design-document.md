# Titan architecture

Titan is a Java-to-SQL build toolchain. This document describes the current module
boundaries; it is not a promise to implement every Java API or a product roadmap.

## Data flow

```text
Titan DSL repository                    Titan repository
schema → model → generated catalog ───→ Java compilation / source analysis
DSL annotations and descriptors ──────→ entry-point discovery and validation
                                        ↓
                              typed intermediate representation
                                        ↓
                           semantic passes and dialect emission
                                        ↓
                            SQL files and structured metadata
                                        ↓
                              package / optional verification
```

The source-composite build substitutes the DSL, codegen, and generation plugin
artifacts from a separate checkout. Core never needs a second copied generator.

## Modules

| Module | Responsibility |
| --- | --- |
| `titan-transpiler` | Compiler-API parsing, feature/null/safety validation, lowering, typed IR, semantic passes, PostgreSQL/MySQL emitters. |
| `titan-gradle-plugin` | Source/schema task wiring, compiler execution, structured packaging, diagnostics/report tasks, install verification. |
| `titan-runtime-jdbc` | Application-side JDBC execution and Java/SQL test helpers; includes driver/test infrastructure dependencies. |
| `titan-management` | Experimental control-plane records and file/JDBC storage adapters. |
| `titan-management-routines` | Management routines compiled with the in-build transpiler; generated SQL is packaged into management resources. |
| `titan-intellij-plugin` | Standalone, experimental IntelliJ integration; outside the core Gradle build. |

## Compiler boundaries

DSL expressions and recognized JDBC shapes lower into the compiler's typed
intermediate representation. JDBC embedded SQL remains native to one target;
this is not a general SQL-dialect translator. Raw SQL and permissive fragments
remain explicit trust boundaries.

Java connection/pool infrastructure does not become a database connection inside
a stored routine. Application execution is separate from compile/package time.
Routine installation and application call routing are also explicit operations.

The compiler performs validation and emits positioned diagnostics, but neither
successful lowering nor a static compatibility report certifies runtime
equivalence, database privileges, performance, or arbitrary raw SQL.

## Extension points and maintenance

[Adding a dialect](adding-a-dialect.md) covers providers, capabilities, emitters,
type mapping, runtime helpers, packaging, and verification. Changes must preserve
SQL parameter order and add appropriate diagnostics and live-database evidence.

The root build supplies shared Java/testing/publication policies. Module build
files declare their dependencies and module-specific generation tasks. The IDE
experiment uses its own wrapper/toolchain.

See [testing](testing.md) for the evidence layers and
[security notes](security-review-sql-text-safety.md) for the limits of compiler checks.
