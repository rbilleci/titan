# Preparing a source or artifact release

Titan's public-source preparation is distinct from making the repository public,
creating a tag/release, publishing artifacts, or certifying production workloads.

The [public launch record](public-launch.md) captures the tested revisions,
dependency audit, and limits of the initial source release.

## Candidate verification

1. Confirm the project license/ownership and third-party notices. Check source,
   Maven POMs, library/source/Javadoc JARs, and any separately distributed IDE ZIP.
2. Audit current files and all relevant history/PR records for secrets, personal
   metadata, private business material, and old repository identities. Removing
   current files does not remove historical copies. Keep audit exports private.
3. Build an isolated source checkout with only the declared Titan DSL dependency,
   Java 21, and an empty Gradle user home; do not rely on Maven-local artifacts.
4. Run the core build, plugin validation, both quickstart targets, and container
   integration suites. Record commands, revisions, test totals/skips, and failures.
5. Generate Maven publication metadata and inspect coordinates, SCM URL, license,
   dependencies, and packaged license/notice files. The root publishes no artifact.
6. Review documentation links, feature/support claims, experimental exclusions,
   security contact, and repository metadata.
   Audit resolved runtime, test, annotation-processor, and buildscript dependencies
   against current advisories; refresh GitHub's manually submitted dependency
   snapshots after dependency changes. Actions is intentionally disabled.
7. Record results in the [preparation plan](public-release-plan.md) or release notes.

## Visibility gates requiring owner action

- Approve any license/ownership decision and the treatment of historical/private
  material. A history rewrite or repository recreation needs separate authorization.
- Establish an actual private vulnerability-reporting/contact route.
- Decide whether to make the repository public. Do not infer this permission from
  a request to prepare source or run tests.
- Make the required Titan DSL source revision accessible to the intended users
  as well; a public Titan checkout alone cannot build against a private dependency.
- Verify repository rules do not require absent paid CI checks.

## Artifact publication is separate

No public artifact registry, signing identity, release credentials, or automated
publishing workflow is configured. Local `publishToMavenLocal` is a development
convenience, not a public distribution channel.

Before publishing, select the registry, verify namespace ownership, pin versions
and dependency revisions, test an independent consumer against the exact staged
artifacts, review dependency licenses/vulnerabilities, and retain checksums and
validation evidence. Set the shared core version in `gradle.properties` and update
documented/example coordinates together. The external DSL version is a separate
dependency, not automatically changed by a core release.

No version tag, Marketplace upload, artifact publication, or public visibility
change is a side effect of a successful local build.
