# Contributing to Titan

Focused issues and pull requests are welcome at
[rbilleci/titan](https://github.com/rbilleci/titan). Discuss substantial API,
compiler, or deployment changes before implementing them. Submit only material
you have the right to contribute; project-owned contributions are GPL-3.0-only
under [LICENSE](LICENSE).
Preserve third-party notices.

## Development

Use JDK 21 and clone [Titan DSL](https://github.com/rbilleci/titan-dsl) beside this
repository. Build through the checked-in wrapper, not a globally installed Gradle:

```bash
./gradlew build
./gradlew -p examples/quickstart build
./gradlew -p examples/quickstart build -PtargetDialect=mysql
```

Run `./gradlew integrationTest` with Docker for live-database changes. See
[testing](docs/testing.md) for focused suites, goldens, and generative validation.
GitHub Actions CI is intentionally absent; include local commands and results in
your PR. Do not enable paid runners or require absent CI checks.

## Change boundaries

- Preserve positioned diagnostics, bind order, SQL-safety boundaries, and explicit
  PostgreSQL/MySQL behavior. Add a regression test for every compiler fix.
- Do not broaden supported-syntax claims on the strength of a rendering assertion
  alone. Exercise affected SQL against the target database where applicable.
- DSL, schema introspection, codegen, and the generation-only plugin belong in
  the separate Titan DSL repository; avoid duplicate implementations here.
- Keep the default build Docker-free and free of pre-existing Maven-local artifacts.
- Do not hand-edit generated files under `build/`. Review intentional golden
  changes alongside the implementation change.
- Keep experimental management/IDE APIs clearly identified. Do not promise stable
  APIs, production support, or registry availability without a corresponding release.
- Never commit credentials, database dumps, private customer material, local
  environment files, or internal commercial plans.

Describe the behavior change, compatibility impact, tests, and documentation
updates in each PR. Keep unrelated edits separate. Be respectful and discuss the
code and evidence rather than the contributor.

Potential vulnerabilities belong in the [private reporting process](SECURITY.md),
not a public issue containing exploit details or credentials.
