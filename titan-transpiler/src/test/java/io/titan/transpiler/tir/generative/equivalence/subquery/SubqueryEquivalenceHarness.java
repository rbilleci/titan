package io.titan.transpiler.tir.generative.equivalence.subquery;

import io.titan.transpiler.tir.generative.shared.SelectReferenceEvaluator;
import io.titan.transpiler.tir.generative.shared.SelectCaseModel;
import io.titan.transpiler.tir.generative.shared.ReferenceResultComparison;
import io.titan.transpiler.tir.generative.shared.ReferenceResultModels.ResultRow;
import io.titan.transpiler.tir.generative.shared.ReferenceResultModels.ReferenceResult;
import io.titan.transpiler.tir.generative.shared.FixtureCatalog;
import io.titan.transpiler.tir.generative.shared.FailureTriageSupport;
import io.titan.transpiler.tir.generative.shared.ArtifactSummaryWriter;
import io.titan.transpiler.tir.LoweredSelectSqlEmitter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.testcontainers.containers.PostgreSQLContainer;

final class SubqueryEquivalenceHarness {

    private static final PostgreSQLContainer<?> POSTGRES = io.titan.test.TestContainers.postgres();


    record Run(
            SubqueryEquivalenceCaseModel.SubqueryEquivalenceCase subqueryCase,
            ReferenceResult expected,
            ReferenceResult subqueryActual,
            ReferenceResult rewrittenActual,
            String mismatchSummary,
            Path artifactDir
    ) {
    }

    Run run(SubqueryEquivalenceProfile profile, long seed, Path tempDir) throws Exception {
        SubqueryEquivalenceCaseModel.SubqueryEquivalenceCase subqueryCase = switch (profile) {
            case SUBQUERY_FORMS -> new SubqueryEquivalenceGenerator().generate(seed);
        };
        Path artifactDir = Files.createDirectories(tempDir.resolve(profile.id() + "-seed-" + Long.toUnsignedString(seed)));
        var fixture = FixtureCatalog.accountsFixture();
        var expected = new SelectReferenceEvaluator().evaluate(subqueryCase.referenceCase(), fixture);

        Files.writeString(artifactDir.resolve("subquery-case.json"), subqueryCase.toStableJson());
        Files.writeString(artifactDir.resolve("subquery-form.java"), subqueryCase.subqueryJavaSource());
        Files.writeString(artifactDir.resolve("rewritten-form.java"), subqueryCase.rewrittenJavaSource());
        Files.writeString(artifactDir.resolve("accounts-fixture.json"), fixture.toStableJson());

        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(),
                POSTGRES.getPassword())) {
            installFixture(connection, fixture);
            Path subqueryDir = Files.createDirectories(artifactDir.resolve("subquery"));
            Path rewrittenDir = Files.createDirectories(artifactDir.resolve("rewritten"));
            String subquerySql = new LoweredSelectSqlEmitter().emitPostgresSelectSql(subqueryCase.subqueryJavaSource(), subqueryDir);
            String rewrittenSql = new LoweredSelectSqlEmitter().emitPostgresSelectSql(subqueryCase.rewrittenJavaSource(), rewrittenDir);
            Files.writeString(artifactDir.resolve("subquery.sql"), subquerySql + System.lineSeparator());
            Files.writeString(artifactDir.resolve("rewritten.sql"), rewrittenSql + System.lineSeparator());

            var subqueryActual = execute(connection, subquerySql, subqueryCase.referenceCase().projections());
            var rewrittenActual = execute(connection, rewrittenSql, subqueryCase.referenceCase().projections());
            String mismatch = compare(expected, subqueryActual, rewrittenActual);
            Files.writeString(artifactDir.resolve("repro.txt"), """
                    Replay this Phase C subquery equivalence case with:

                    ./gradlew :titan-transpiler:test \
                      --tests io.titan.transpiler.tir.generative.equivalence.subquery.SubqueryEquivalenceReplayTest \
                      -Dtitan.phasec.profile=%s \
                      -Dtitan.phasec.seed=%s \
                      --no-daemon
                    """.formatted(profile.id(), Long.toUnsignedString(seed)));
            if (mismatch != null) {
                Files.writeString(artifactDir.resolve("mismatch.txt"), mismatch + System.lineSeparator());
            }
            FailureTriageSupport.Triage triage = FailureTriageSupport.forMismatch(mismatch);
            ArtifactSummaryWriter.write(
                    artifactDir,
                    "Phase C subquery equivalence artifact summary",
                    mismatch == null ? "green" : "mismatch",
                    Map.of(
                            "Phase", "C",
                            "Profile", profile.id(),
                            "Failure bucket", triage.bucket(),
                            "Likely layer hint", triage.likelyLayerHint(),
                            "Family", subqueryCase.family().id()),
                    mismatch == null
                            ? List.of("subquery-case.json", "subquery-form.java", "rewritten-form.java", "subquery.sql", "rewritten.sql", "repro.txt")
                            : List.of("subquery-case.json", "subquery-form.java", "rewritten-form.java", "subquery.sql", "rewritten.sql", "mismatch.txt", "repro.txt"));
            return new Run(subqueryCase, expected, subqueryActual, rewrittenActual, mismatch, artifactDir);
        }
    }

    private static String compare(
            ReferenceResult expected,
            ReferenceResult subqueryActual,
            ReferenceResult rewrittenActual
    ) {
        ReferenceResultComparison comparison = new ReferenceResultComparison();
        String subqueryMismatch = comparison.summarizeMismatch(expected, subqueryActual);
        if (subqueryMismatch != null) {
            return "Subquery-vs-reference mismatch: " + subqueryMismatch;
        }
        String rewrittenMismatch = comparison.summarizeMismatch(expected, rewrittenActual);
        if (rewrittenMismatch != null) {
            return "Rewritten-vs-reference mismatch: " + rewrittenMismatch;
        }
        String pairMismatch = comparison.summarizeMismatch(subqueryActual, rewrittenActual);
        if (pairMismatch != null) {
            return "Subquery-vs-rewritten mismatch: " + pairMismatch;
        }
        return null;
    }

    private static void installFixture(Connection connection, FixtureCatalog.FixtureTable fixture) throws Exception {
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("DROP TABLE IF EXISTS public.accounts");
            stmt.execute("""
                    CREATE TABLE public.accounts (
                      id INTEGER NOT NULL,
                      email TEXT NULL,
                      active BOOLEAN NULL,
                      plan_code TEXT NULL,
                      login_count INTEGER NULL
                    )
                    """);
            for (FixtureCatalog.FixtureRow row : fixture.rows()) {
                stmt.execute("INSERT INTO public.accounts (id, email, active, plan_code, login_count) VALUES ("
                        + sqlLiteral(row.values().get("id")) + ", "
                        + sqlLiteral(row.values().get("email")) + ", "
                        + sqlLiteral(row.values().get("active")) + ", "
                        + sqlLiteral(row.values().get("plan_code")) + ", "
                        + sqlLiteral(row.values().get("login_count")) + ")");
            }
        }
    }

    private static ReferenceResult execute(
            Connection connection,
            String sql,
            List<SelectCaseModel.Projection> projections
    ) throws Exception {
        List<ResultRow> rows = new ArrayList<>();
        try (Statement stmt = connection.createStatement(); ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                Map<String, Object> values = new LinkedHashMap<>();
                for (int i = 0; i < projections.size(); i++) {
                    values.put(projections.get(i).alias(), rs.getObject(i + 1));
                }
                rows.add(new ResultRow(values));
            }
        }
        rows.sort(java.util.Comparator.comparing(ResultRow::toStableJson));
        return new ReferenceResult(List.copyOf(rows));
    }

    private static String sqlLiteral(Object value) {
        if (value == null) {
            return "NULL";
        }
        if (value instanceof String s) {
            return "'" + s.replace("'", "''") + "'";
        }
        if (value instanceof Boolean b) {
            return b ? "TRUE" : "FALSE";
        }
        if (value instanceof Number number) {
            return number.toString();
        }
        throw new IllegalArgumentException("Unsupported fixture SQL literal: " + value.getClass().getName());
    }
}
