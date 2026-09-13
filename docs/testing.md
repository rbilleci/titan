# Testing Titan

Use Java 21, the checked-in wrapper, and a sibling Titan DSL checkout.

## Core and consumer checks

```bash
./gradlew build
./gradlew -p examples/quickstart build
./gradlew -p examples/quickstart build -PtargetDialect=mysql
./gradlew integrationTest
```

The default build excludes tests tagged `docker`. Integration tests require a
working Docker daemon and can pull PostgreSQL/MySQL images. The tested container
targets are PostgreSQL 16 and MySQL 8.4. The quickstart checks source consumption,
generation, transpilation, packaging, and reports without a database.

Reports are under each module's `build/reports/tests/<task>/` and
`build/test-results/<task>/`. A skipped seeded replay test is not evidence that a
seeded replay ran; record skips and exact commands with release results.

## Focused checks

```bash
./gradlew :titan-transpiler:test
./gradlew :titan-gradle-plugin:validatePlugins :titan-gradle-plugin:test
./gradlew :titan-runtime-jdbc:test
./gradlew :titan-transpiler:integrationTest --tests '*JdbcEmitterDeployabilityIT'
```

The standalone IDE experiment has a different build; see
[IDE notes](intellij-plugin-bootstrap.md).

## Golden and generative layers

Emitter golden files pin expected SQL. Update them only alongside intentional
compiler changes, and review the SQL diff:

```bash
./gradlew :titan-transpiler:test --tests io.titan.transpiler.tir.GoldenSqlTest -Dtitan.golden.update=true
./gradlew :titan-transpiler:test --tests io.titan.transpiler.tir.JdbcGoldenTest -Dtitan.jdbcgolden.update=true
```

The scripts expose bounded conformance, differential, equivalence, and mutation
runs:

```bash
bash scripts/validate-conformance.sh
bash scripts/validate-differential.sh
bash scripts/validate-equivalence.sh
bash scripts/validate-mutation.sh
# Or the maintained combined sequence:
bash scripts/validate-generative-all.sh
```

Inspect each script's profile and Docker requirements before running it.
Changing a golden or using a smaller profile does not establish correctness on
the broader input space. Pair byte-level checks with live deployment/execution
and application-specific parity tests.

GitHub Actions CI is not configured. Report local results honestly; no external
green status should be inferred from an unrun suite.
