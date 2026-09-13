package io.titan.transpiler.tir.generative.shared;

import io.titan.transpiler.tir.LoweredSelectSqlEmitter;
import io.titan.transpiler.tir.generative.shared.ReferenceResultModels.ReferenceResult;
import io.titan.transpiler.tir.generative.shared.ReferenceResultModels.ResultRow;
import java.io.IOException;
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
 * MySQL twin of {@link SelectPostgresSqlHarness} (plan 5.3): renders the same generated case
 * to the same Java DSL source, transpiles it for MySQL through the full production pipeline,
 * executes the emitted SELECT on the singleton MySQL container, and compares against the same
 * {@link SelectReferenceEvaluator} result through the same comparison machinery. Cases the
 * transpiler capability-rejects for MySQL (TITAN-E001) are recorded as skips, not failures —
 * see {@link MySqlDifferentialSupport}.
 */
public final class SelectMySqlSqlHarness {

    public record SelectRun(
            String generatedJavaSource,
            String sql,
            ReferenceResult expected,
            ReferenceResult actual,
            String mismatchSummary,
            String capabilitySkipReason,
            Path artifactDir
    ) {
        public boolean capabilitySkipped() {
            return capabilitySkipReason != null;
        }
    }

    public SelectRun runSelectCase(
            Connection connection,
            SelectCaseModel.SelectCase selectCase,
            FixtureCatalog.FixtureTable fixtureTable,
            Path artifactRoot
    ) throws Exception {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(selectCase, "selectCase");
        Objects.requireNonNull(fixtureTable, "fixtureTable");
        Objects.requireNonNull(artifactRoot, "artifactRoot");

        Path artifactDir = Files.createDirectories(artifactRoot.resolve(selectCase.profileId() + "-mysql-artifacts"));
        String generatedJavaSource = SelectPostgresSqlHarness.renderJavaSource(selectCase);
        Files.writeString(artifactDir.resolve("generated-case.java"), generatedJavaSource);
        Files.writeString(artifactDir.resolve("case.json"), selectCase.toStableJson());
        Files.writeString(artifactDir.resolve("fixture.json"), fixtureTable.toStableJson());

        String sql;
        try {
            sql = new LoweredSelectSqlEmitter().emitMySqlSelectSql(generatedJavaSource, artifactDir);
        } catch (RuntimeException transpileFailure) {
            String skipReason = MySqlDifferentialSupport.capabilitySkipReason(transpileFailure);
            if (skipReason == null) {
                throw transpileFailure;
            }
            Files.writeString(artifactDir.resolve("capability-skip.txt"), skipReason + System.lineSeparator());
            return new SelectRun(generatedJavaSource, null, null, null, null, skipReason, artifactDir);
        }
        MySqlDifferentialSupport.installAccountsFixture(connection, fixtureTable);
        ReferenceResult expected = new SelectReferenceEvaluator().evaluate(selectCase, fixtureTable);
        ReferenceResult actual = executeSelect(connection, selectCase, sql);
        String mismatchSummary = new ReferenceResultComparison().summarizeMismatch(expected, actual);

        writeArtifacts(artifactDir, sql, expected, actual, mismatchSummary, selectCase);
        return new SelectRun(generatedJavaSource, sql, expected, actual, mismatchSummary, null, artifactDir);
    }

    public ReferenceResult executeSelect(
            Connection connection,
            SelectCaseModel.SelectCase selectCase,
            String sql
    ) throws Exception {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(selectCase, "selectCase");
        Objects.requireNonNull(sql, "sql");

        List<ResultRow> rows = new ArrayList<>();
        try (Statement stmt = connection.createStatement(); ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                Map<String, Object> values = new LinkedHashMap<>();
                for (int i = 0; i < selectCase.projections().size(); i++) {
                    SelectCaseModel.Projection projection = selectCase.projections().get(i);
                    Object value = rs.getObject(i + 1);
                    if (value instanceof Number number && projection.expression().valueType() == SelectCaseModel.ValueType.INTEGER) {
                        value = number.intValue();
                    }
                    if (value instanceof Number number && projection.expression().valueType() == SelectCaseModel.ValueType.BOOLEAN) {
                        // MySQL BOOLEAN is TINYINT(1); some drivers/expressions surface it as a
                        // number — normalize to the Boolean the reference evaluator produces.
                        value = number.intValue() != 0;
                    }
                    values.put(projection.alias(), value);
                }
                rows.add(new ResultRow(values));
            }
        }
        return new ReferenceResult(List.copyOf(rows));
    }

    private static void writeArtifacts(
            Path artifactDir,
            String sql,
            ReferenceResult expected,
            ReferenceResult actual,
            String mismatchSummary,
            SelectCaseModel.SelectCase selectCase
    ) throws IOException {
        Files.writeString(artifactDir.resolve("mysql-select.sql"), sql + System.lineSeparator());
        Files.writeString(artifactDir.resolve("reference-result.json"), expected.toStableJson() + System.lineSeparator());
        Files.writeString(artifactDir.resolve("sql-result.json"), actual.toStableJson() + System.lineSeparator());
        Files.writeString(artifactDir.resolve("repro.txt"), """
                Replay this bounded select case on the MySQL leg with:

                source ~/.sdkman/bin/sdkman-init.sh && ./gradlew :titan-transpiler:integrationTest \
                  --tests io.titan.transpiler.tir.generative.differential.SelectDifferentialMySqlReplayTest \
                  -Dtitan.phaseb.profile=%s \
                  -Dtitan.phaseb.seed=<seed> \
                  --no-daemon

                Profile: %s
                """.formatted(selectCase.profileId(), selectCase.profileId()));
        if (mismatchSummary != null) {
            Files.writeString(artifactDir.resolve("mismatch.txt"), mismatchSummary + System.lineSeparator());
        }
        FailureTriageSupport.Triage triage = FailureTriageSupport.forMismatch(mismatchSummary);
        ArtifactSummaryWriter.write(
                artifactDir,
                "Select MySQL SQL artifact summary",
                mismatchSummary == null ? "green" : "mismatch",
                Map.of(
                        "Profile", selectCase.profileId(),
                        "Dialect", "mysql",
                        "Failure bucket", triage.bucket(),
                        "Likely layer hint", triage.likelyLayerHint(),
                        "Case", selectCase.toStableJson()),
                mismatchSummary == null
                        ? List.of("generated-case.java", "mysql-select.sql", "reference-result.json", "sql-result.json", "repro.txt")
                        : List.of("generated-case.java", "mysql-select.sql", "reference-result.json", "sql-result.json", "mismatch.txt", "repro.txt"));
    }
}
