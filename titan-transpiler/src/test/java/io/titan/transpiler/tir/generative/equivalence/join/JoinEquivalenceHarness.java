package io.titan.transpiler.tir.generative.equivalence.join;

import io.titan.transpiler.tir.generative.shared.JoinReferenceEvaluator;
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

final class JoinEquivalenceHarness {

    private static final PostgreSQLContainer<?> POSTGRES = io.titan.test.TestContainers.postgres();


    record Run(
            JoinEquivalenceCaseModel.JoinEquivalenceCase joinCase,
            ReferenceResult expected,
            ReferenceResult explicitActual,
            ReferenceResult helperActual,
            String mismatchSummary,
            Path artifactDir
    ) {
    }

    Run run(JoinEquivalenceProfile profile, long seed, Path tempDir) throws Exception {
        JoinEquivalenceCaseModel.JoinEquivalenceCase joinCase = switch (profile) {
            case JOIN_FORMS -> new JoinEquivalenceGenerator().generate(seed);
        };
        Path artifactDir = Files.createDirectories(tempDir.resolve(profile.id() + "-seed-" + Long.toUnsignedString(seed)));
        var accounts = FixtureCatalog.accountsFixture();
        var plans = FixtureCatalog.plansFixture();
        var planFamilies = FixtureCatalog.planFamiliesFixture();
        var expected = new JoinReferenceEvaluator().evaluate(joinCase.referenceCase(), accounts, plans, planFamilies);
        var curatedSeed = JoinEquivalenceSeedCorpus.findBySeed(seed);

        Files.writeString(artifactDir.resolve("join-case.json"), joinCase.toStableJson());
        Files.writeString(artifactDir.resolve("explicit-case.java"), joinCase.explicitJavaSource());
        Files.writeString(artifactDir.resolve("helper-case.java"), joinCase.helperJavaSource());
        Files.writeString(artifactDir.resolve("accounts-fixture.json"), accounts.toStableJson());
        Files.writeString(artifactDir.resolve("plans-fixture.json"), plans.toStableJson());
        Files.writeString(artifactDir.resolve("plan-families-fixture.json"), planFamilies.toStableJson());

        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(),
                POSTGRES.getPassword())) {
            installFixtures(connection, accounts, plans, planFamilies);
            Path explicitDir = Files.createDirectories(artifactDir.resolve("explicit"));
            Path helperDir = Files.createDirectories(artifactDir.resolve("helper"));
            String explicitSql = new LoweredSelectSqlEmitter().emitPostgresSelectSql(joinCase.explicitJavaSource(), explicitDir);
            String helperSql = new LoweredSelectSqlEmitter().emitPostgresSelectSql(joinCase.helperJavaSource(), helperDir);
            Files.writeString(artifactDir.resolve("explicit.sql"), explicitSql + System.lineSeparator());
            Files.writeString(artifactDir.resolve("helper.sql"), helperSql + System.lineSeparator());
            Files.writeString(artifactDir.resolve("repro.txt"), """
                    Replay this Phase C join equivalence case with:

                    ./gradlew :titan-transpiler:test \
                      --tests io.titan.transpiler.tir.generative.equivalence.join.JoinEquivalenceReplayTest \
                      -Dtitan.phasec.profile=%s \
                      -Dtitan.phasec.seed=%s \
                      --no-daemon
                    %s""".formatted(
                    profile.id(),
                    Long.toUnsignedString(seed),
                    curatedSeed != null && curatedSeed.originPhase() != null
                            ? "\nOriginating finding: " + curatedSeed.originPhase() + " seed " + Long.toUnsignedString(curatedSeed.originSeed()) + "\n"
                            : ""));
            var explicitActual = execute(connection, explicitSql);
            var helperActual = execute(connection, helperSql);
            String mismatch = compare(expected, explicitActual, helperActual);
            if (mismatch != null) {
                Files.writeString(artifactDir.resolve("mismatch.txt"), mismatch + System.lineSeparator());
            }
            FailureTriageSupport.Triage triage = FailureTriageSupport.forMismatch(mismatch);
            ArtifactSummaryWriter.write(
                    artifactDir,
                    "Phase C join equivalence artifact summary",
                    mismatch == null ? "green" : "mismatch",
                    provenanceFields(profile.id(), triage, joinCase.family().id(), curatedSeed),
                    mismatch == null
                            ? List.of("join-case.json", "explicit-case.java", "helper-case.java", "explicit.sql", "helper.sql", "repro.txt")
                            : List.of("join-case.json", "explicit-case.java", "helper-case.java", "explicit.sql", "helper.sql", "mismatch.txt", "repro.txt"));
            return new Run(joinCase, expected, explicitActual, helperActual, mismatch, artifactDir);
        }
    }

    private static Map<String, String> provenanceFields(
            String profileId,
            FailureTriageSupport.Triage triage,
            String familyId,
            JoinEquivalenceSeedCorpus.CuratedSeed curatedSeed
    ) {
        LinkedHashMap<String, String> fields = new LinkedHashMap<>();
        fields.put("Phase", "C");
        fields.put("Profile", profileId);
        fields.put("Failure bucket", triage.bucket());
        fields.put("Likely layer hint", triage.likelyLayerHint());
        fields.put("Family", familyId);
        if (curatedSeed != null && curatedSeed.originPhase() != null) {
            fields.put("Originating finding", curatedSeed.originPhase() + " seed " + Long.toUnsignedString(curatedSeed.originSeed()));
        }
        return fields;
    }

    private static String compare(
            ReferenceResult expected,
            ReferenceResult explicitActual,
            ReferenceResult helperActual
    ) {
        ReferenceResultComparison comparison = new ReferenceResultComparison();
        String explicitMismatch = comparison.summarizeMismatch(expected, explicitActual);
        if (explicitMismatch != null) {
            return "Explicit-vs-reference mismatch: " + explicitMismatch;
        }
        String helperMismatch = comparison.summarizeMismatch(expected, helperActual);
        if (helperMismatch != null) {
            return "Helper-vs-reference mismatch: " + helperMismatch;
        }
        String pairMismatch = comparison.summarizeMismatch(explicitActual, helperActual);
        if (pairMismatch != null) {
            return "Explicit-vs-helper mismatch: " + pairMismatch;
        }
        return null;
    }

    private static void installFixtures(
            Connection connection,
            FixtureCatalog.FixtureTable accounts,
            FixtureCatalog.FixtureTable plans,
            FixtureCatalog.FixtureTable planFamilies
    ) throws Exception {
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("DROP TABLE IF EXISTS public.plan_families");
            stmt.execute("DROP TABLE IF EXISTS public.plans");
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
            stmt.execute("""
                    CREATE TABLE public.plans (
                      code TEXT NOT NULL,
                      name TEXT NOT NULL,
                      paid BOOLEAN NOT NULL,
                      family_code TEXT NOT NULL
                    )
                    """);
            stmt.execute("""
                    CREATE TABLE public.plan_families (
                      code TEXT NOT NULL,
                      label TEXT NOT NULL
                    )
                    """);
            for (FixtureCatalog.FixtureRow row : accounts.rows()) {
                stmt.execute("INSERT INTO public.accounts (id, email, active, plan_code, login_count) VALUES ("
                        + sqlLiteral(row.values().get("id")) + ", "
                        + sqlLiteral(row.values().get("email")) + ", "
                        + sqlLiteral(row.values().get("active")) + ", "
                        + sqlLiteral(row.values().get("plan_code")) + ", "
                        + sqlLiteral(row.values().get("login_count")) + ")");
            }
            for (FixtureCatalog.FixtureRow row : plans.rows()) {
                stmt.execute("INSERT INTO public.plans (code, name, paid, family_code) VALUES ("
                        + sqlLiteral(row.values().get("code")) + ", "
                        + sqlLiteral(row.values().get("name")) + ", "
                        + sqlLiteral(row.values().get("paid")) + ", "
                        + sqlLiteral(row.values().get("family_code")) + ")");
            }
            for (FixtureCatalog.FixtureRow row : planFamilies.rows()) {
                stmt.execute("INSERT INTO public.plan_families (code, label) VALUES ("
                        + sqlLiteral(row.values().get("code")) + ", "
                        + sqlLiteral(row.values().get("label")) + ")");
            }
        }
    }

    private static ReferenceResult execute(Connection connection, String sql) throws Exception {
        List<ResultRow> rows = new ArrayList<>();
        try (Statement stmt = connection.createStatement(); ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                Map<String, Object> values = new LinkedHashMap<>();
                values.put("account_id", rs.getObject(1));
                values.put("plan_name", rs.getObject(2));
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
