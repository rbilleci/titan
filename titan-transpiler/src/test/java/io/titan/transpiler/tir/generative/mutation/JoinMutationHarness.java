package io.titan.transpiler.tir.generative.mutation;

import io.titan.transpiler.tir.generative.shared.JoinReferenceEvaluator;
import io.titan.transpiler.tir.generative.shared.ReferenceResultComparison;
import io.titan.transpiler.tir.generative.shared.ReferenceResultModels.ResultRow;
import io.titan.transpiler.tir.generative.shared.ReferenceResultModels.ReferenceResult;
import io.titan.transpiler.tir.generative.shared.FixtureCatalog;
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

final class JoinMutationHarness {

    private static final PostgreSQLContainer<?> POSTGRES = io.titan.test.TestContainers.postgres();


    record Run(
            JoinMutationCaseModel.JoinMutationCase mutationCase,
            ReferenceResult expected,
            ReferenceResult actual,
            String mismatchSummary,
            Path artifactDir
    ) {
    }

    Run run(JoinMutationProfile profile, long seed, Path tempDir) throws Exception {
        if (profile != JoinMutationProfile.JOIN_FORMS) {
            throw new IllegalArgumentException("Unsupported Phase D join-mutation profile: " + profile.id());
        }
        JoinMutationCaseModel.JoinMutationCase mutationCase = new JoinMutationGenerator().generate(seed);
        Path artifactDir = Files.createDirectories(tempDir.resolve(profile.id() + "-seed-" + Long.toUnsignedString(seed)));
        var accounts = FixtureCatalog.accountsFixture();
        var plans = FixtureCatalog.plansFixture();
        var expected = new JoinReferenceEvaluator().evaluate(
                mutationCase.baseCase(), accounts, plans, FixtureCatalog.planFamiliesFixture());

        Files.writeString(artifactDir.resolve("accounts-fixture.json"), accounts.toStableJson());
        Files.writeString(artifactDir.resolve("plans-fixture.json"), plans.toStableJson());

        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(),
                POSTGRES.getPassword())) {
            installFixtures(connection, accounts, plans);
            Path mutatedDir = Files.createDirectories(artifactDir.resolve("mutated"));
            String sql = new LoweredSelectSqlEmitter().emitPostgresSelectSql(mutationCase.mutatedJavaSource(), mutatedDir);
            Files.writeString(artifactDir.resolve("mutated.sql"), sql + System.lineSeparator());
            var actual = execute(connection, sql);
            String mismatch = new ReferenceResultComparison().summarizeMismatch(expected, actual);
            new JoinMutationArtifactWriter().write(artifactDir, mutationCase, expected, actual, mismatch);
            return new Run(mutationCase, expected, actual, mismatch, artifactDir);
        }
    }

    private static void installFixtures(
            Connection connection,
            FixtureCatalog.FixtureTable accounts,
            FixtureCatalog.FixtureTable plans
    ) throws Exception {
        try (Statement stmt = connection.createStatement()) {
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
