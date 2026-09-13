package io.titan.transpiler.tir.generative.equivalence;

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

final class FormEquivalenceHarness {

    private static final PostgreSQLContainer<?> POSTGRES = io.titan.test.TestContainers.postgres();


    record Run(
            FormEquivalenceCaseModel.FormEquivalenceCase formCase,
            ReferenceResult expected,
            ReferenceResult helperActual,
            ReferenceResult explicitActual,
            String mismatchSummary,
            Path artifactDir
    ) {
    }

    Run run(FormEquivalenceProfile profile, long seed, Path tempDir) throws Exception {
        FormEquivalenceCaseModel.FormEquivalenceCase formCase = switch (profile) {
            case HELPER_EXPLICIT -> new FormEquivalenceGenerator().generate(seed);
        };
        Path artifactDir = Files.createDirectories(tempDir.resolve(profile.id() + "-seed-" + Long.toUnsignedString(seed)));
        var fixture = FixtureCatalog.accountsFixture();
        var expected = new SelectReferenceEvaluator().evaluate(formCase.referenceCase(), fixture);

        Files.writeString(artifactDir.resolve("form-case.json"), formCase.toStableJson());
        Files.writeString(artifactDir.resolve("helper-case.java"), formCase.helperJavaSource());
        Files.writeString(artifactDir.resolve("explicit-case.java"), formCase.explicitJavaSource());
        Files.writeString(artifactDir.resolve("accounts-fixture.json"), fixture.toStableJson());

        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(),
                POSTGRES.getPassword())) {
            installFixture(connection, fixture);
            Path helperDir = Files.createDirectories(artifactDir.resolve("helper"));
            Path explicitDir = Files.createDirectories(artifactDir.resolve("explicit"));
            String helperSql = new LoweredSelectSqlEmitter().emitPostgresSelectSql(formCase.helperJavaSource(), helperDir);
            String explicitSql = new LoweredSelectSqlEmitter().emitPostgresSelectSql(formCase.explicitJavaSource(), explicitDir);
            Files.writeString(artifactDir.resolve("helper.sql"), helperSql + System.lineSeparator());
            Files.writeString(artifactDir.resolve("explicit.sql"), explicitSql + System.lineSeparator());

            var helperActual = execute(connection, helperSql, formCase.referenceCase().projections());
            var explicitActual = execute(connection, explicitSql, formCase.referenceCase().projections());
            String mismatch = compare(expected, helperActual, explicitActual);
            Files.writeString(artifactDir.resolve("repro.txt"), """
                    Replay this Phase C form equivalence case with:

                    ./gradlew :titan-transpiler:test \
                      --tests io.titan.transpiler.tir.generative.equivalence.FormEquivalenceReplayTest \
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
                    "Phase C form equivalence artifact summary",
                    mismatch == null ? "green" : "mismatch",
                    Map.of(
                            "Phase", "C",
                            "Profile", profile.id(),
                            "Failure bucket", triage.bucket(),
                            "Likely layer hint", triage.likelyLayerHint(),
                            "Family", formCase.family().id()),
                    mismatch == null
                            ? List.of("form-case.json", "helper-case.java", "explicit-case.java", "helper.sql", "explicit.sql", "repro.txt")
                            : List.of("form-case.json", "helper-case.java", "explicit-case.java", "helper.sql", "explicit.sql", "mismatch.txt", "repro.txt"));
            return new Run(formCase, expected, helperActual, explicitActual, mismatch, artifactDir);
        }
    }

    private static String compare(
            ReferenceResult expected,
            ReferenceResult helperActual,
            ReferenceResult explicitActual
    ) {
        ReferenceResultComparison comparison = new ReferenceResultComparison();
        String helperMismatch = comparison.summarizeMismatch(expected, helperActual);
        if (helperMismatch != null) {
            return "Helper-vs-reference mismatch: " + helperMismatch;
        }
        String explicitMismatch = comparison.summarizeMismatch(expected, explicitActual);
        if (explicitMismatch != null) {
            return "Explicit-vs-reference mismatch: " + explicitMismatch;
        }
        String pairMismatch = comparison.summarizeMismatch(helperActual, explicitActual);
        if (pairMismatch != null) {
            return "Helper-vs-explicit mismatch: " + pairMismatch;
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
