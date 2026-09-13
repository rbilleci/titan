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
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MySQL twin of {@link SubqueryDifferentialPostgresSqlHarness} (plan 5.3): same generated
 * EXISTS/NOT EXISTS/correlated/scalar/CTE case, same Java reference, executed on the singleton
 * MySQL container. Capability-rejected cases (TITAN-E001) become skips.
 */
final class SubqueryDifferentialMySqlHarness {

    record Run(
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

    Run run(
            Connection connection,
            SubqueryDifferentialCaseModel.Case subqueryCase,
            FixtureCatalog.FixtureTable fixtureTable,
            Path artifactRoot
    ) throws Exception {
        Path artifactDir = Files.createDirectories(artifactRoot.resolve(subqueryCase.family().id() + "-mysql-artifacts"));
        String source = SubqueryDifferentialPostgresSqlHarness.renderJavaSource(subqueryCase);
        Files.writeString(artifactDir.resolve("case.json"), subqueryCase.toStableJson());
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
            return new Run(source, null, null, null, null, skipReason, artifactDir);
        }
        MySqlDifferentialSupport.installAccountsFixture(connection, fixtureTable);
        ReferenceResult expected = new SubqueryDifferentialReferenceEvaluator().evaluate(subqueryCase, fixtureTable);
        ReferenceResult actual = execute(connection, subqueryCase, sql);
        String mismatch = new ReferenceResultComparison().summarizeMismatch(expected, actual);

        Files.writeString(artifactDir.resolve("mysql-select.sql"), sql + System.lineSeparator());
        Files.writeString(artifactDir.resolve("reference-result.json"), expected.toStableJson() + System.lineSeparator());
        Files.writeString(artifactDir.resolve("sql-result.json"), actual.toStableJson() + System.lineSeparator());
        Files.writeString(artifactDir.resolve("repro.txt"), """
                Replay this bounded subquery differential case on the MySQL leg with:

                source ~/.sdkman/bin/sdkman-init.sh && ./gradlew :titan-transpiler:integrationTest \
                  --tests io.titan.transpiler.tir.generative.differential.SubqueryDifferentialMySqlReplayTest \
                  -Dtitan.phaseb.profile=%s \
                  -Dtitan.phaseb.seed=<seed> \
                  --no-daemon

                Profile: %s
                Family: %s
                """.formatted(subqueryCase.profileId(), subqueryCase.profileId(), subqueryCase.family().id()));
        if (mismatch != null) {
            Files.writeString(artifactDir.resolve("mismatch.txt"), mismatch + System.lineSeparator());
        }
        FailureTriageSupport.Triage triage = FailureTriageSupport.forMismatch(mismatch);
        ArtifactSummaryWriter.write(
                artifactDir,
                "Phase B subquery MySQL artifact summary",
                mismatch == null ? "green" : "mismatch",
                Map.of(
                        "Phase", "B",
                        "Profile", subqueryCase.profileId(),
                        "Dialect", "mysql",
                        "Failure bucket", triage.bucket(),
                        "Likely layer hint", triage.likelyLayerHint(),
                        "Family", subqueryCase.family().id()),
                mismatch == null
                        ? List.of("generated-case.java", "mysql-select.sql", "reference-result.json", "sql-result.json", "repro.txt")
                        : List.of("generated-case.java", "mysql-select.sql", "reference-result.json", "sql-result.json", "mismatch.txt", "repro.txt"));
        return new Run(source, sql, expected, actual, mismatch, null, artifactDir);
    }

    private ReferenceResult execute(
            Connection connection,
            SubqueryDifferentialCaseModel.Case subqueryCase,
            String sql
    ) throws Exception {
        List<ResultRow> rows = new ArrayList<>();
        try (Statement stmt = connection.createStatement(); ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                Map<String, Object> values = new LinkedHashMap<>();
                switch (subqueryCase.family()) {
                    case EXISTS_ENTERPRISE -> {
                        values.put("id", rs.getObject(1));
                        values.put("plan_code", rs.getObject(2));
                    }
                    case NOT_EXISTS_VIP_ACTIVE, CORRELATED_EXISTS_ACTIVE_PLAN, CORRELATED_NOT_EXISTS_NULL_PLAN, CORRELATED_SCALAR_EMAIL_BY_PLAN, CORRELATED_SCALAR_NULL_OR_ABSENT_EMAIL_BY_PLAN, SCALAR_EMAIL_LOOKUP, SCALAR_NULL_EMAIL_LOOKUP, CTE_ACTIVE_ROWS -> {
                        values.put("id", rs.getObject(1));
                        values.put("email", rs.getObject(2));
                    }
                }
                rows.add(new ResultRow(values));
            }
        }
        rows.sort(Comparator.comparing(ResultRow::toStableJson));
        return new ReferenceResult(List.copyOf(rows));
    }
}
