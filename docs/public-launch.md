# Public source launch — 2026-09-14

Titan is an early-access, GPL-3.0-only source release. This is not an artifact
registry release, stable-API commitment, or production security certification.

## Tested source revisions

- Titan: `a422627c4086866c15bac19b0848a088c4e74e36`.
- Titan DSL/codegen: `863f78a7575a435ec307dc39d90e1ad630053b7a`.

Later launch-documentation and ignore-rule commits do not change the tested code.
Clone the repositories side by side as described in the README; no Maven-local
publication is required.

## Verification

The core build, Gradle plugin validation, PostgreSQL 16/MySQL 8.4 integration
suites, and Maven POM generation passed in both the working checkout and a fresh
source checkout with an initially empty Gradle user home. The fresh core build
executed all 72 tasks.

The same fresh source pair passed both PostgreSQL and MySQL quickstart builds,
the standalone DSL `run runFeatures` example, and schema-codegen `build run`.
Titan DSL's full build and integration suites passed in that fresh checkout too.

| Component | Passed | Skipped | Failures/errors |
| --- | ---: | ---: | ---: |
| Core unit tests | 1,557 | 1 | 0 |
| Core integration tests | 197 | 19 | 0 |
| Standalone experimental IDE tests | 29 | 0 | 0 |

Core skips are opt-in seeded replay entry points without replay properties. The
IDE's `test jar` check is separate; instrumentation remains disabled and no
Marketplace distribution or platform compatibility certification is claimed.

Binary, source, and Javadoc JARs carry the license and third-party notices. Maven
metadata uses the canonical repository and GPL-3.0-only. Existing non-fatal
compiler/Javadoc warnings do not constitute complete API documentation.

## Dependency and privacy checks

- Updated PostgreSQL JDBC to 42.7.13 and MySQL Connector/J to 26.7.0. The latter
  is the driver's version, not a change to the tested MySQL 8.4 server.
- Constrained the Testcontainers archive dependency to Commons Compress 1.28.0.
  Updated affected transitive libraries in the experimental IDE build tooling.
- A combined OSV query of 108 unique resolved Maven package/version pairs across
  Titan, Titan DSL, and IDE runtime, test, annotation-processor, and buildscript
  configurations returned no matched advisories. This is a point-in-time check;
  it excludes container images, Gradle distributions, and bundled IDE SDKs, and
  does not guarantee the absence of undiscovered vulnerabilities.
- Gitleaks 8.30.1 reported no secrets in the cleaned reachable Git history,
  reachable object contents, or exported GitHub issue/PR discussion text. The
  audit included the five existing Titan DSL dependency-update PR heads. Private
  recovery archives and audit logs remain outside both repositories.

Resolved dependency snapshots were submitted directly to GitHub for Dependabot
alerts, without Actions. These are manual snapshots: refresh them and repeat the
local dependency audit when dependencies change. They are not continuous CI.

## Repository controls

Titan DSL was made public first, followed by Titan, with explicit owner approval.
Both repositories have private vulnerability reporting, Dependabot alerts,
secret scanning, and secret-scanning push protection enabled. Protected `main`
blocks force pushes and deletion, applies to administrators, and requires linear
history and resolved review conversations. No CI status checks or additional
reviewer are required; GitHub Actions is disabled. Normal maintainer pushes remain
possible. No release workflow was added.

Upstream references: [PostgreSQL JDBC releases](https://github.com/pgjdbc/pgjdbc/releases),
[MySQL Connector/J 26.7.0](https://dev.mysql.com/doc/relnotes/connector-j/en/news-26-7-0.html),
and [OSV](https://osv.dev/).

## Separate future release work

Registry selection, staged-artifact consumer tests, signing/provenance, version
tags, maintained stable branches, and IDE distribution review remain separate
work. No registry artifacts, GitHub release, or Marketplace package were published.
