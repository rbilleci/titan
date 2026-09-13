# Titan Management — experimental

Management records and storage primitives for hosts integrating Titan: drafts,
validation reports, artifact references, deployments, operation registrations,
usage reports, audit records, and idempotent transactional mutations.

This is not a hosted control plane, CLI, or HTTP service. APIs, diagnostics, and
storage formats are experimental and may change.

## Implemented components

- Record models, canonical JSON, command validation, and input hashing.
- File-backed audit, idempotency, and transactional mutation stores, intended
  for tests and single-process use.
- JDBC-backed stores using `management_*` tables. Mutation logic is implemented
  by Titan-generated routines from `titan-management-routines`.
- Explicit schema/routine bootstrap through `ManagementSchemaInstaller`.
  Constructing a store does not automatically provision its database.

The generated PostgreSQL and MySQL SQL bundles are included as resources in the
management JAR. Production code uses JDK APIs only; the integrating application
must provide its JDBC driver, connections, credentials, and deployment policy.
Testcontainers and database drivers declared here are test dependencies.

## Verification

From the repository root:

```bash
./gradlew :titan-management:test :titan-management-routines:test
./gradlew :titan-management:integrationTest :titan-management-routines:integrationTest
```

The integration suites require Docker. `JdbcManagementStoreDogfoodIT` exercises
durability, concurrency, and generated-routine behavior on PostgreSQL 16 and
MySQL 8.4. These tests are evidence for the covered scenarios, not a production
availability or security guarantee.

See the [architecture](../docs/design-document.md),
[security policy](../SECURITY.md), and [testing guide](../docs/testing.md).
