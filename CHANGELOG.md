# Changelog

## Unreleased

- License project-owned files under GPL-3.0-only and preserve separate third-party notices.
- Prepare source onboarding, documentation, project policies, and artifact metadata
  for a public early-access release.
- Pin Gradle distribution checksums and package license/notices in binary,
  source, and Javadoc artifacts.
- Add a runnable source-composite example covering schema generation, JDBC-input
  transpilation, scalar Java functions, SQL packaging, and analysis reports.
- Add Kotlin configuration actions for the full plugin's transpiler, deployment,
  and verification settings.
- Declare explicit non-cacheable policies for packaging, live verification, and
  the legacy lifecycle task so Gradle plugin validation passes.
- Correct reserved-word integration assertions to require schema-qualified table
  names, while retaining live deployment and execution checks for both dialects.
- Keep internal planning/commercial records out of the public source candidate;
  historical privacy review remains a separate visibility gate.

The build version is `0.1.0`; this entry does not announce a published artifact,
tag, stable API, or production support commitment.
