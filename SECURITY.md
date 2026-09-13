# Security

Titan is early-access software. Fixes target the current main revision; no stable
release branches or response-time SLA are offered.

## Reporting

Do not post credentials, sensitive data, or exploit details in a public issue.
Use **Report a vulnerability** in the repository's Security tab, or the
[private reporting channel](https://github.com/rbilleci/titan/security/advisories/new).
Private vulnerability reporting is enabled. If temporarily unavailable, request
a private security contact without including sensitive details. The maintainer
must establish that channel before you send the report.

## Trust boundaries

- Treat source code, DDL, identifiers, and raw SQL fragments as trusted developer
  input. Titan is not a sandbox for untrusted projects or build scripts.
- Prefer prepared-statement bindings. Strict SQL-safety validation rejects many
  unsafe forms, but does not prove all generated or supplied SQL safe.
- `@SqlSafety(PERMISSIVE)` and build/class-level overrides can permit runtime
  SQL fragments. Review these deliberately and inspect the effective-scope report.
  `strictMode` is deprecated and has no security effect.
- Inspect generated routines, grants, ownership, schema/search-path assumptions,
  extensions, and `@SecurityDefiner` behavior before installing them.
- `titanVerifyInstall` executes DDL. It uses disposable containers unless you
  configure a verification JDBC URL; that URL's database will be modified.
- Rollback scripts may drop objects containing data. They do not replace backups,
  restore scheduler state universally, or make a deployment risk-free.
- Source constants, bind values, SQL files, exceptions, schema snapshots, reports,
  and logs can contain sensitive information. Review what you publish or retain.
- Credentials supplied via environment variables still exist in the build process;
  use dedicated least-privileged accounts and trusted developer/build machines.
- Generated behavior depends on database version, SQL mode, collation, extensions,
  privileges, and application transaction boundaries. Test the intended environment.

The [security review notes](docs/security-review-sql-text-safety.md) describe the
verification evidence and its limits. Neither passing tests nor repository
publication is a production security certification.
