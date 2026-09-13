# Deployment readiness checklist

Public source availability is not production readiness. Titan is early-access;
complete a workload-specific review before deploying generated routines.

## Required for each workload

- Pin the Titan/DSL revisions and record the JDK, database image/version, SQL mode,
  collation/time zone, extensions, and schema source.
- Compile a supported subset and review all diagnostics, raw SQL, permissive
  scopes, generated signatures, object metadata, and SQL files.
- Exercise Java/SQL parity on representative inputs and database state, including
  nulls, overflow/precision, exceptions, retries, and transaction boundaries.
- Deploy to a disposable staging database; check required schemas/tables,
  privileges, security-definer ownership, scheduler support, and installation drift.
- Test concurrency, idempotency, failure recovery, and operational observability.
  Never log production secrets as part of parity/debug evidence.
- Measure latency, throughput, database CPU/locks, and connection behavior against
  the existing implementation under realistic load.
- Establish backups and a staged rollout/rollback procedure. Generated rollback
  SQL is only one input; data restoration and scheduler state may need separate work.
- Assign an owner for migrations, runtime support, vulnerability intake, and upgrades.

## Experimental components

The management modules are storage/routine experiments, not a complete supported
control plane. The IntelliJ plugin is separately built and not a Marketplace
release. Do not infer production readiness from inclusion in this repository.

## Source-release gates

Licensing, source audit, documentation, artifact packaging, and public-visibility
decisions are covered by [releasing](releasing.md) and the
[executed preparation plan](public-release-plan.md). Independent security review
and application-specific operational approval cannot be replaced by a passing
repository build.
