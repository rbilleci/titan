package io.titan.transpiler.tir.generative.equivalence.composition;

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

final class CompositionEquivalenceHarness {

    private static final PostgreSQLContainer<?> POSTGRES = io.titan.test.TestContainers.postgres();


    record Run(
            CompositionEquivalenceCaseModel.CompositionEquivalenceCase compositionCase,
            ReferenceResult expected,
            ReferenceResult namedCteActual,
            ReferenceResult inlineViewActual,
            String mismatchSummary,
            Path artifactDir
    ) {
    }

    Run run(CompositionEquivalenceProfile profile, long seed, Path tempDir) throws Exception {
        CompositionEquivalenceCaseModel.CompositionEquivalenceCase compositionCase = switch (profile) {
            case CTE_INLINE_VIEW -> new CompositionEquivalenceGenerator().generate(seed);
        };
        Path artifactDir = Files.createDirectories(tempDir.resolve(profile.id() + "-seed-" + Long.toUnsignedString(seed)));
        var fixture = FixtureCatalog.accountsFixture();
        var expected = new SelectReferenceEvaluator().evaluate(compositionCase.referenceCase(), fixture);

        Files.writeString(artifactDir.resolve("composition-case.json"), compositionCase.toStableJson());
        Files.writeString(artifactDir.resolve("named-cte-case.java"), compositionCase.namedCteJavaSource());
        Files.writeString(artifactDir.resolve("inline-view-case.java"), compositionCase.inlineViewJavaSource());
        Files.writeString(artifactDir.resolve("accounts-fixture.json"), fixture.toStableJson());

        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(),
                POSTGRES.getPassword())) {
            installFixture(connection, fixture);
            Path namedDir = Files.createDirectories(artifactDir.resolve("named-cte"));
            Path inlineDir = Files.createDirectories(artifactDir.resolve("inline-view"));
            String namedSql = new LoweredSelectSqlEmitter().emitPostgresSelectSql(compositionCase.namedCteJavaSource(), namedDir);
            String inlineSql = new LoweredSelectSqlEmitter().emitPostgresSelectSql(compositionCase.inlineViewJavaSource(), inlineDir);
            Files.writeString(artifactDir.resolve("named-cte.sql"), namedSql + System.lineSeparator());
            Files.writeString(artifactDir.resolve("inline-view.sql"), inlineSql + System.lineSeparator());

            var namedActual = execute(connection, namedSql, compositionCase.referenceCase().projections());
            var inlineActual = execute(connection, inlineSql, compositionCase.referenceCase().projections());
            String mismatch = compare(expected, namedActual, inlineActual);
            Files.writeString(artifactDir.resolve("repro.txt"), """
                    Replay this Phase C composition equivalence case with:

                    ./gradlew :titan-transpiler:test \
                      --tests io.titan.transpiler.tir.generative.equivalence.composition.CompositionEquivalenceReplayTest \
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
                    "Phase C composition equivalence artifact summary",
                    mismatch == null ? "green" : "mismatch",
                    Map.of(
                            "Phase", "C",
                            "Profile", profile.id(),
                            "Failure bucket", triage.bucket(),
                            "Likely layer hint", triage.likelyLayerHint(),
                            "Family", compositionCase.family().id()),
                    mismatch == null
                            ? List.of("composition-case.json", "named-cte-case.java", "inline-view-case.java", "named-cte.sql", "inline-view.sql", "repro.txt")
                            : List.of("composition-case.json", "named-cte-case.java", "inline-view-case.java", "named-cte.sql", "inline-view.sql", "mismatch.txt", "repro.txt"));
            return new Run(compositionCase, expected, namedActual, inlineActual, mismatch, artifactDir);
        }
    }

    private static String compare(
            ReferenceResult expected,
            ReferenceResult namedActual,
            ReferenceResult inlineActual
    ) {
        ReferenceResultComparison comparison = new ReferenceResultComparison();
        String namedMismatch = comparison.summarizeMismatch(expected, namedActual);
        if (namedMismatch != null) {
            return "Named-CTE-vs-reference mismatch: " + namedMismatch;
        }
        String inlineMismatch = comparison.summarizeMismatch(expected, inlineActual);
        if (inlineMismatch != null) {
            return "Inline-view-vs-reference mismatch: " + inlineMismatch;
        }
        String pairMismatch = comparison.summarizeMismatch(namedActual, inlineActual);
        if (pairMismatch != null) {
            return "Named-CTE-vs-inline-view mismatch: " + pairMismatch;
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
