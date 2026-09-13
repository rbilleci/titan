package io.titan.transpiler.tir.generative.differential;

import io.titan.transpiler.tir.LoweredSelectSqlEmitter;
import io.titan.transpiler.tir.generative.shared.ArtifactSummaryWriter;
import io.titan.transpiler.tir.generative.shared.FailureTriageSupport;
import io.titan.transpiler.tir.generative.shared.FixtureCatalog;
import io.titan.transpiler.tir.generative.shared.MySqlDifferentialSupport;
import io.titan.transpiler.tir.generative.shared.ReferenceResultComparison;
import io.titan.transpiler.tir.generative.shared.ReferenceResultModels.ReferenceResult;
import io.titan.transpiler.tir.generative.shared.ReferenceResultModels.ResultRow;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * MySQL twin of {@link AggregationDifferentialPostgresSqlHarness} (plan 5.3). Same generated
 * aggregation case and Java reference; the SQL leg runs on the singleton MySQL container.
 * MySQL-specific result-shape normalization happens here: SUM over INTEGER returns DECIMAL on
 * MySQL (BIGINT on PostgreSQL) and BOOLEAN group keys surface as TINYINT — both are widened or
 * narrowed to the reference evaluator's types before comparison, exactly like the PostgreSQL
 * harness narrows its aggregates. Capability-rejected cases (TITAN-E001) become skips.
 */
final class AggregationDifferentialMySqlHarness {

    record AggregationRun(
            String generatedJavaSource,
            String sql,
            ReferenceResult expected,
            ReferenceResult actual,
            String mismatchSummary,
            String capabilitySkipReason,
            Path artifactDir
    ) {
        boolean capabilitySkipped() {
            return capabilitySkipReason != null;
        }
    }

    AggregationRun run(
            Connection connection,
            AggregationDifferentialCaseModel.AggregationCase aggregationCase,
            FixtureCatalog.FixtureTable fixtureTable,
            Path artifactRoot
    ) throws Exception {
        Objects.requireNonNull(connection, "connection");
        Path artifactDir = Files.createDirectories(artifactRoot.resolve(aggregationCase.profileId() + "-aggregation-mysql-artifacts"));
        String source = AggregationDifferentialPostgresSqlHarness.renderJavaSource(aggregationCase);
        Files.writeString(artifactDir.resolve("case.json"), aggregationCase.toStableJson());
        Files.writeString(artifactDir.resolve("generated-case.java"), source);
        Files.writeString(artifactDir.resolve("fixture.json"), fixtureTable.toStableJson());

        String sql;
        try {
            sql = new LoweredSelectSqlEmitter().emitMySqlSelectSql(source, artifactDir);
        } catch (RuntimeException transpileFailure) {
            String skipReason = MySqlDifferentialSupport.capabilitySkipReason(transpileFailure);
            if (skipReason == null) {
                throw transpileFailure;
            }
            Files.writeString(artifactDir.resolve("capability-skip.txt"), skipReason + System.lineSeparator());
            return new AggregationRun(source, null, null, null, null, skipReason, artifactDir);
        }
        MySqlDifferentialSupport.installAccountsFixture(connection, fixtureTable);
        ReferenceResult expected = new AggregationDifferentialReferenceEvaluator().evaluate(aggregationCase, fixtureTable);
        ReferenceResult actual = execute(connection, aggregationCase, sql);
        String mismatch = new ReferenceResultComparison().summarizeMismatch(expected, actual);

        Files.writeString(artifactDir.resolve("mysql-select.sql"), sql + System.lineSeparator());
        Files.writeString(artifactDir.resolve("reference-result.json"), expected.toStableJson() + System.lineSeparator());
        Files.writeString(artifactDir.resolve("sql-result.json"), actual.toStableJson() + System.lineSeparator());
        Files.writeString(artifactDir.resolve("repro.txt"), """
                Replay this bounded aggregation differential case on the MySQL leg with:

                source ~/.sdkman/bin/sdkman-init.sh && ./gradlew :titan-transpiler:integrationTest \
                  --tests io.titan.transpiler.tir.generative.differential.AggregationDifferentialMySqlReplayTest \
                  -Dtitan.phaseb.profile=%s \
                  -Dtitan.phaseb.seed=<seed> \
                  --no-daemon

                Profile: %s
                Aggregate: %s
                Having: %s
                """.formatted(
                aggregationCase.profileId(),
                aggregationCase.profileId(),
                aggregationCase.aggregateKind().name(),
                aggregationCase.havingKind().name()));
        if (mismatch != null) {
            Files.writeString(artifactDir.resolve("mismatch.txt"), mismatch + System.lineSeparator());
        }
        FailureTriageSupport.Triage triage = FailureTriageSupport.forMismatch(mismatch);
        ArtifactSummaryWriter.write(
                artifactDir,
                "Phase B aggregation MySQL artifact summary",
                mismatch == null ? "green" : "mismatch",
                Map.of(
                        "Phase", "B",
                        "Profile", aggregationCase.profileId(),
                        "Dialect", "mysql",
                        "Failure bucket", triage.bucket(),
                        "Likely layer hint", triage.likelyLayerHint(),
                        "Aggregate", aggregationCase.aggregateKind().name(),
                        "Having", aggregationCase.havingKind().name()),
                mismatch == null
                        ? List.of("generated-case.java", "mysql-select.sql", "reference-result.json", "sql-result.json", "repro.txt")
                        : List.of("generated-case.java", "mysql-select.sql", "reference-result.json", "sql-result.json", "mismatch.txt", "repro.txt"));
        return new AggregationRun(source, sql, expected, actual, mismatch, null, artifactDir);
    }

    private ReferenceResult execute(
            Connection connection,
            AggregationDifferentialCaseModel.AggregationCase aggregationCase,
            String sql
    ) throws Exception {
        List<ResultRow> rows = new ArrayList<>();
        try (Statement stmt = connection.createStatement(); ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                Map<String, Object> values = new LinkedHashMap<>();
                Object groupKey = rs.getObject(1);
                if (groupKey instanceof Number number && aggregationCase.groupKey() == AggregationDifferentialCaseModel.GroupKey.ACTIVE) {
                    // MySQL BOOLEAN group keys can surface as TINYINT numbers depending on the
                    // expression shape; the reference evaluator produces Booleans.
                    groupKey = number.intValue() != 0;
                }
                values.put(aggregationCase.groupAlias(), groupKey);
                Object aggregate = rs.getObject(2);
                if (aggregate instanceof Number number) {
                    aggregate = switch (aggregationCase.aggregateKind()) {
                        case COUNT_ALL -> number.longValue();
                        case SUM_LOGIN_COUNT, MIN_LOGIN_COUNT, MAX_LOGIN_COUNT -> number.intValue();
                    };
                }
                values.put(aggregationCase.aggregateAlias(), aggregate);
                rows.add(new ResultRow(values));
            }
        }
        rows.sort((left, right) -> AggregationDifferentialReferenceEvaluator.compareGroupKeys(
                left.values().get(aggregationCase.groupAlias()),
                right.values().get(aggregationCase.groupAlias())));
        return new ReferenceResult(List.copyOf(rows));
    }
}
