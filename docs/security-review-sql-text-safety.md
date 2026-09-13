# SQL-text safety: review notes

Status: engineering evidence, not human security certification.

These notes supersede the older internal audit's universal safety claims. Titan
now has JDBC input and explicit permissive SQL paths; it would be inaccurate to
claim that no runtime text can ever reach SQL.

## Review surfaces

| Surface | Relevant evidence | Limits |
| --- | --- | --- |
| Structured emitter identifiers and literals | Reserved-word deployment tests, emitter/golden tests. | Raw fragments and runtime DSL text are not universally structured/quoted. |
| Runtime value binding | `JdbcExecutorBindingIT`, executor unit tests. | Identifiers/SQL structure are not made safe by value bindings; some DSL composition paths capture literals. |
| Strict SQL validation | Feature-validator, JDBC recognizer, lowering, and safety-mode tests. | Supported-shape analysis is not a general SQL parser/security proof. |
| Permissive SQL | `SqlSafetyResolverTest`, scope-report and splice tests. | Explicitly relaxed source/class/build scopes require human review. |
| Privileged routines | Security-definer lowering and deployment tests. | Application authorization, role ownership, grants, and database configuration remain deployment responsibilities. |
| Install verification and rollback | Plugin verification/deployability tests. | Verification executes DDL; rollback can discard data and does not replace backups. |

The core baseline commands are `./gradlew build` and
`./gradlew integrationTest`. For claims about a particular revision, consult
the recorded release verification rather than assuming an older test count or
audit statement still applies.

## Before production use

Review the actual generated SQL and all raw/permissive paths, minimize privileges,
avoid sensitive logging, test the exact database configuration, and validate
failure/rollback behavior with representative data. Arrange an independent
security review for production-sensitive usage.

See [SECURITY](../SECURITY.md) for reporting and trust boundaries, and
[deployment readiness](production-readiness-plan.md) for operational checks.
