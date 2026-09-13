# Public source-release preparation

Status: public source launch complete on 2026-09-14 with explicit owner approval.
Titan DSL was made public first, followed by Titan. The earlier history cleanup
recreated the repository from a clean snapshot; subsequent commits contain only
public-release work. See the [launch verification record](public-launch.md).

## Scope and baseline

Prepare Titan as a source-consumable, early-access Java-to-SQL toolchain. This is
not a stable-API promise, production security certification, or registry release.
Work started on `chore/public-release-preparation`. The original source, Git
history, and audit records are preserved in a private recovery archive outside
the repository; they are not part of the public candidate.

Initial findings:

- No root license, contributor/security guidance, or release checklist.
- Maven metadata still uses the former repository owner and a proprietary
  placeholder; artifact notices/source documentation need consistent packaging.
- README onboarding cannot be followed from a fresh consumer checkout and says
  JDBC lowering is unimplemented despite existing lowering/deployability tests.
- Public guides mix supported behavior, future design, commercial plans, and
  private execution records. Some examples hard-code credentials or use obsolete
  configuration, and the IDE plugin advertises an unverified contact address.
- Titan DSL/codegen is an external source dependency, not a module here. Neither
  repository currently provides a configured public artifact-release channel.
- Experimental management and IntelliJ components need explicit scope/validation
  boundaries. No funded GitHub Actions CI should be introduced.

## Execution plan

1. **Audit and preserve.** Back up source/history privately; scan reachable branch
   and PR history plus current files; review attribution, document metadata,
   personal information, and internal/commercial material. Rewrite history only
   with separate owner authorization (subsequently received).
2. **License and project essentials.** Confirm the owner's license choice; add
   license/notices, contributor/security guidance, changelog, issue/PR templates,
   and safe ignore rules. Normalize repository URLs and publication metadata.
3. **Public documentation.** Present verified features and limits. Separate
   runtime SQL construction from transpilation, document JDBC's native-dialect
   subset, and move non-public planning records out of the tracked candidate while
   preserving local recovery copies. Keep essential technical documentation.
4. **Reproducible adoption.** Add a complete source-composite consumer with DDL,
   Java routines, build configuration, and checked SQL/package outputs. Ensure
   onboarding does not rely on pre-existing Maven-local publications.
5. **Verification and packaging.** Run the Docker-free core build, container
   integration suites, the example, documentation checks, and publication/JAR
   inspection. Exercise an isolated source checkout. Validate or explicitly
   exclude the standalone experimental IDE plugin from the core release claim.
6. **Review handoff.** Set GitHub description/topics; record exact results and
   outstanding owner decisions. The initial preparation-branch handoff was
   superseded by explicit owner authorization to recreate the private repository
   and put the clean snapshot on `main`. Visibility was subsequently changed only
   after separate owner authorization. Artifact publication remains out of scope.

## Completion criteria

- A new developer can reproduce the documented source build and example.
- The README distinguishes tested features, supported subsets, and experiments.
- Approved licensing and attribution are consistent in source and artifacts.
- No known secret or internal business document remains in the current candidate.
- Validation failures are resolved or documented with a narrowly scoped release
  exclusion; public-readiness claims do not exceed the evidence.
- Historical/privacy findings and private security-contact setup are explicit
  owner-facing visibility gates, not silently treated as solved by tree cleanup.

## Execution record

### Implemented

- Confirmed GPL v3 with the owner and applied GPL-3.0-only, matching Titan DSL.
  Added the complete license, third-party notices, contribution/security policies,
  changelog, issue/PR templates, and shared publication metadata.
- Rewrote the public introduction and guides around implemented behavior and
  explicit limits. Removed obsolete/internal commercial and execution records
  from the candidate tree, including two Office documents with personal metadata.
  Private source/history backups preserve all removed material.
- Added a standalone source-composite quickstart exercising DDL catalog generation,
  native JDBC-input transpilation, a scalar function, packaging, and reports for
  each dialect. Added configurable `titanDslDir` source discovery.
- Fixed three Gradle task cache-policy validation errors and missing Kotlin
  configuration actions; added regression coverage. Fixed Javadoc markup/link
  errors exposed by building documentation JARs.
- Updated the reserved-word integration test to expect schema-qualified table
  names; retained actual PostgreSQL/MySQL deployment and execution assertions.
- Added source/Javadoc JARs and license/notices to all core JARs. Aligned the
  experimental IDE version/notices and removed the unverified vendor email.
- Verified wrapper JARs against the official Gradle 8.10.2 wrapper digest (both
  checked-in launchers match); pinned official distribution SHA-256 values for
  the core Gradle 9.5.1 and IDE Gradle 8.10.2 downloads.
- Restored the repository description/topics after authorized private recreation.
  GitHub Actions is disabled and no workflows were added. No visibility or
  artifact-publication changes were made. The fresh root commit uses a project
  identity and GitHub noreply address, without co-author trailers.

### Verification evidence — 2026-09-13

Environment: Temurin JDK 21.0.11; core Gradle 9.5.1; Docker-backed PostgreSQL 16
and MySQL 8.4 suites. Titan DSL source revision:
`62d44a5dd0ea832757cc1db58eba63bb7b0d8077`.

The core build, plugin validation, and full integration task set passed:

```bash
./gradlew build integrationTest generatePomFileForMavenPublication \
  :titan-gradle-plugin:generatePomFileForPluginMavenPublication \
  :titan-gradle-plugin:generatePomFileForTitanGradlePluginPluginMarkerMavenPublication \
  --no-daemon --console=plain
```

| Core module | Docker-free passed | Integration passed | Skipped |
| --- | ---: | ---: | ---: |
| Gradle plugin | 73 | 14 | 0 |
| Management | 58 | 12 | 0 |
| Management routines | 9 | 2 | 0 |
| Runtime JDBC | 46 | 22 | 0 |
| Transpiler | 1,370 | 145 | 1 unit + 19 integration |
| **Total** | **1,556** | **195** | **20** |

All skips are opt-in seeded replay entry points without replay properties, not
failed database setup. Zero failures/errors remain. Javadoc succeeds with
non-fatal missing-comment/tag warnings; this is not complete API documentation.

- Both PostgreSQL and MySQL quickstart builds passed in the working checkout.
- Standalone IDE `test jar` passed: 29 tests, zero failures/skips. Its JAR includes
  license/notices and version 0.1.0. Instrumentation remains disabled; no plugin
  distribution/Marketplace certification is claimed.
- Inspected all 15 core binary/source/Javadoc JARs for license/notices and all
  five Maven POMs for coordinates, GPL-3.0-only, repository/SCM metadata, and
  version 0.1.0. No artifact registry was configured or used.
- All 51 local links across 21 Markdown files resolve; `git diff --check` passes.
- Gitleaks 8.30.1 found no secrets in the candidate tree, reachable Git objects,
  scanned commit diffs, or exported GitHub issue/PR discussion text. The audit
  included 906 reachable commits and 41 PR heads/discussions (zero issues).
  This is a bounded scan, not a guarantee that undiscovered secrets do not exist.
- The isolated source build passed with only Titan and Titan DSL source and an
  initially empty Gradle user home: all 62 tasks executed, with no Maven-local
  publications. Both PostgreSQL and MySQL quickstart checks also passed there.

Private audit exports, logs, recovery copies, and source/history archives are
outside the repository and must not be committed or uploaded.

### Visibility gates — completed 2026-09-14

The authorized history cleanup removed the original repository's 41 PR records
and old branches from the replacement repository. The original local Git database
(including old refs, objects, and reflogs) was moved to private recovery storage;
the active checkout contains only the clean history. GitHub recreation is not a
claim to erase copies in other people's clones or the provider's internal backups.
License selection does not independently prove ownership of every contribution;
retain third-party attribution and confirm provenance before distribution.

1. **Private security contact:** GitHub private vulnerability reporting is enabled
   in both repositories; the security policies link directly to that channel.
2. **Source access and visibility:** the owner authorized publication. Titan DSL
   and Titan are public, in that order, with GPL-3.0-only licensing.
3. **Repository rules:** `main` is protected against force pushes and deletion,
   including administrator enforcement, with linear history and resolved review
   conversations required. No CI checks or additional reviewer are mandatory.
   GitHub Actions remains disabled in both repositories.
4. **Security monitoring:** Dependabot alerts, secret scanning, and secret-scanning
   push protection are enabled. Resolved dependency snapshots were submitted
   directly without Actions; these need manual refresh after dependency changes.

Registry publishing, a stable API release, production certification, and the
experimental IDE's distribution review remain separate workstreams, not implied
by completing this source-preparation plan.
