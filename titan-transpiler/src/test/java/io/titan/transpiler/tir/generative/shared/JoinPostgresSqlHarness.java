package io.titan.transpiler.tir.generative.shared;

import io.titan.transpiler.tir.LoweredSelectSqlEmitter;
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
import java.util.Objects;

public final class JoinPostgresSqlHarness {

    public record JoinRun(
            String generatedJavaSource,
            String sql,
            ReferenceResult expected,
            ReferenceResult actual,
            String mismatchSummary,
            Path artifactDir
    ) {
    }

    public JoinRun runJoinCase(
            Connection connection,
            JoinCaseModel.JoinCase joinCase,
            Path artifactRoot
    ) throws Exception {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(joinCase, "joinCase");
        Objects.requireNonNull(artifactRoot, "artifactRoot");

        var accounts = FixtureCatalog.accountsFixture();
        var plans = switch (joinCase.family()) {
            case INNER_JOIN_DUPLICATE_PLAN_MATCHES,
                 LEFT_JOIN_DUPLICATE_NULL_EXTENSION -> FixtureCatalog.plansFixtureWithDuplicateFree();
            case INNER_JOIN_ACTIVE_ACCOUNTS,
                 INNER_JOIN_PAID_PLANS,
                 LEFT_JOIN_ACTIVE_NULL_EXTENSION,
                 LEFT_JOIN_PAID_FILTER_PRESERVES_NULLS,
                 LEFT_JOIN_PAID_WHERE_COLLAPSES_NULL_EXTENSION,
                 TWO_JOIN_ACTIVE_PLAN_FAMILY,
                 TWO_JOIN_DUPLICATE_SECOND_HOP_MULTIPLICATION,
                 TWO_JOIN_SECOND_HOP_FILTER,
                 LEFT_TWO_JOIN_NULL_EXTENSION,
                 LEFT_TWO_JOIN_SECOND_HOP_FILTER,
                 LEFT_TWO_JOIN_SECOND_HOP_WHERE_COLLAPSES_NULL_EXTENSION -> FixtureCatalog.plansFixture();
        };
        var planFamilies = switch (joinCase.family()) {
            case TWO_JOIN_DUPLICATE_SECOND_HOP_MULTIPLICATION -> FixtureCatalog.planFamiliesFixtureWithDuplicateGrowth();
            case INNER_JOIN_ACTIVE_ACCOUNTS,
                 INNER_JOIN_PAID_PLANS,
                 INNER_JOIN_DUPLICATE_PLAN_MATCHES,
                 LEFT_JOIN_ACTIVE_NULL_EXTENSION,
                 LEFT_JOIN_PAID_FILTER_PRESERVES_NULLS,
                 LEFT_JOIN_PAID_WHERE_COLLAPSES_NULL_EXTENSION,
                 TWO_JOIN_ACTIVE_PLAN_FAMILY,
                 TWO_JOIN_SECOND_HOP_FILTER,
                 LEFT_JOIN_DUPLICATE_NULL_EXTENSION,
                 LEFT_TWO_JOIN_NULL_EXTENSION,
                 LEFT_TWO_JOIN_SECOND_HOP_FILTER,
                 LEFT_TWO_JOIN_SECOND_HOP_WHERE_COLLAPSES_NULL_EXTENSION -> FixtureCatalog.planFamiliesFixture();
        };
        Path artifactDir = Files.createDirectories(artifactRoot.resolve(joinCase.family().id() + "-artifacts"));
        String source = renderJavaSource(joinCase);
        Files.writeString(artifactDir.resolve("case.json"), joinCase.toStableJson());
        Files.writeString(artifactDir.resolve("generated-case.java"), source);
        Files.writeString(artifactDir.resolve("accounts-fixture.json"), accounts.toStableJson());
        Files.writeString(artifactDir.resolve("plans-fixture.json"), plans.toStableJson());
        Files.writeString(artifactDir.resolve("plan-families-fixture.json"), planFamilies.toStableJson());

        String sql = new LoweredSelectSqlEmitter().emitPostgresSelectSql(source, artifactDir);
        installFixtures(connection, accounts, plans, planFamilies);
        var expected = new JoinReferenceEvaluator().evaluate(joinCase, accounts, plans, planFamilies);
        var actual = execute(connection, sql);
        String mismatch = new ReferenceResultComparison().summarizeMismatch(expected, actual);

        Files.writeString(artifactDir.resolve("postgres-select.sql"), sql + System.lineSeparator());
        Files.writeString(artifactDir.resolve("reference-result.json"), expected.toStableJson() + System.lineSeparator());
        Files.writeString(artifactDir.resolve("sql-result.json"), actual.toStableJson() + System.lineSeparator());
        Files.writeString(artifactDir.resolve("repro.txt"), """
                Replay this bounded join case with:

                source ~/.sdkman/bin/sdkman-init.sh && ./gradlew :titan-transpiler:test \
                  --tests io.titan.transpiler.tir.generative.differential.JoinDifferentialReplayTest \
                  -Dtitan.phaseb.profile=%s \
                  -Dtitan.phaseb.seed=<seed> \
                  --no-daemon

                Profile: %s
                Family: %s
                """.formatted(joinCase.profileId(), joinCase.profileId(), joinCase.family().id()));
        if (mismatch != null) {
            Files.writeString(artifactDir.resolve("mismatch.txt"), mismatch + System.lineSeparator());
        }
        FailureTriageSupport.Triage triage = FailureTriageSupport.forMismatch(mismatch);
        ArtifactSummaryWriter.write(
                artifactDir,
                "Join SQL artifact summary",
                mismatch == null ? "green" : "mismatch",
                Map.of(
                        "Phase", "B",
                        "Profile", joinCase.profileId(),
                        "Failure bucket", triage.bucket(),
                        "Likely layer hint", triage.likelyLayerHint(),
                        "Family", joinCase.family().id()),
                mismatch == null
                        ? List.of("generated-case.java", "postgres-select.sql", "reference-result.json", "sql-result.json", "repro.txt")
                        : List.of("generated-case.java", "postgres-select.sql", "reference-result.json", "sql-result.json", "mismatch.txt", "repro.txt"));
        return new JoinRun(source, sql, expected, actual, mismatch, artifactDir);
    }

    private static void installFixtures(
            Connection connection,
            FixtureCatalog.FixtureTable accounts,
            FixtureCatalog.FixtureTable plans,
            FixtureCatalog.FixtureTable planFamilies
    ) throws Exception {
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("DROP TABLE IF EXISTS public.accounts_fixture");
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
        rows.sort(Comparator.comparing(ResultRow::toStableJson));
        return new ReferenceResult(List.copyOf(rows));
    }

    static String renderJavaSource(JoinCaseModel.JoinCase joinCase) {
        String query = switch (joinCase.family()) {
            case INNER_JOIN_ACTIVE_ACCOUNTS -> """
                    select(ACCOUNTS.ID, PLANS.NAME)
                            .from(ACCOUNTS)
                            .join(PLANS).on(ACCOUNTS.PLAN_CODE.eqColumn(PLANS.CODE))
                            .where(ACCOUNTS.ACTIVE.eq(true))
                            .orderBy(ACCOUNTS.ID.asc())
                            .fetch();
                    """;
            case INNER_JOIN_PAID_PLANS -> """
                    select(ACCOUNTS.ID, PLANS.NAME)
                            .from(ACCOUNTS)
                            .join(PLANS).on(ACCOUNTS.PLAN_CODE.eqColumn(PLANS.CODE))
                            .where(PLANS.PAID.eq(true))
                            .orderBy(ACCOUNTS.ID.asc())
                            .fetch();
                    """;
            case INNER_JOIN_DUPLICATE_PLAN_MATCHES -> """
                    select(ACCOUNTS.ID, PLANS.NAME)
                            .from(ACCOUNTS)
                            .join(PLANS).on(ACCOUNTS.PLAN_CODE.eqColumn(PLANS.CODE))
                            .where(ACCOUNTS.ACTIVE.eq(true))
                            .orderBy(ACCOUNTS.ID.asc(), PLANS.NAME.asc())
                            .fetch();
                    """;
            case LEFT_JOIN_ACTIVE_NULL_EXTENSION -> """
                    select(ACCOUNTS.ID, PLANS.NAME)
                            .from(ACCOUNTS)
                            .leftJoin(PLANS).on(ACCOUNTS.PLAN_CODE.eqColumn(PLANS.CODE))
                            .where(ACCOUNTS.ACTIVE.eq(true))
                            .orderBy(ACCOUNTS.ID.asc())
                            .fetch();
                    """;
            case LEFT_JOIN_PAID_FILTER_PRESERVES_NULLS -> """
                    select(ACCOUNTS.ID, PLANS.NAME)
                            .from(ACCOUNTS)
                            .leftJoin(PLANS).on(ACCOUNTS.PLAN_CODE.eqColumn(PLANS.CODE)
                                    .and(PLANS.PAID.eq(true)))
                            .where(ACCOUNTS.ACTIVE.eq(true))
                            .orderBy(ACCOUNTS.ID.asc())
                            .fetch();
                    """;
            case LEFT_JOIN_PAID_WHERE_COLLAPSES_NULL_EXTENSION -> """
                    select(ACCOUNTS.ID, PLANS.NAME)
                            .from(ACCOUNTS)
                            .leftJoin(PLANS).on(ACCOUNTS.PLAN_CODE.eqColumn(PLANS.CODE))
                            .where(ACCOUNTS.ACTIVE.eq(true)
                                    .and(PLANS.PAID.eq(true)))
                            .orderBy(ACCOUNTS.ID.asc())
                            .fetch();
                    """;
            case TWO_JOIN_ACTIVE_PLAN_FAMILY -> """
                    select(ACCOUNTS.ID, PLAN_FAMILIES.LABEL)
                            .from(ACCOUNTS)
                            .join(PLANS).on(ACCOUNTS.PLAN_CODE.eqColumn(PLANS.CODE))
                            .join(PLAN_FAMILIES).on(PLANS.FAMILY_CODE.eqColumn(PLAN_FAMILIES.CODE))
                            .where(ACCOUNTS.ACTIVE.eq(true))
                            .orderBy(ACCOUNTS.ID.asc())
                            .fetch();
                    """;
            case TWO_JOIN_DUPLICATE_SECOND_HOP_MULTIPLICATION -> """
                    select(ACCOUNTS.ID, PLAN_FAMILIES.LABEL)
                            .from(ACCOUNTS)
                            .join(PLANS).on(ACCOUNTS.PLAN_CODE.eqColumn(PLANS.CODE))
                            .join(PLAN_FAMILIES).on(PLANS.FAMILY_CODE.eqColumn(PLAN_FAMILIES.CODE))
                            .where(ACCOUNTS.ACTIVE.eq(true))
                            .orderBy(ACCOUNTS.ID.asc(), PLAN_FAMILIES.LABEL.asc())
                            .fetch();
                    """;
            case TWO_JOIN_SECOND_HOP_FILTER -> """
                    select(ACCOUNTS.ID, PLAN_FAMILIES.LABEL)
                            .from(ACCOUNTS)
                            .join(PLANS).on(ACCOUNTS.PLAN_CODE.eqColumn(PLANS.CODE))
                            .join(PLAN_FAMILIES).on(PLANS.FAMILY_CODE.eqColumn(PLAN_FAMILIES.CODE)
                                    .and(PLAN_FAMILIES.LABEL.eq("Growth")))
                            .where(ACCOUNTS.ACTIVE.eq(true))
                            .orderBy(ACCOUNTS.ID.asc())
                            .fetch();
                    """;
            case LEFT_JOIN_DUPLICATE_NULL_EXTENSION -> """
                    select(ACCOUNTS.ID, PLANS.NAME)
                            .from(ACCOUNTS)
                            .leftJoin(PLANS).on(ACCOUNTS.PLAN_CODE.eqColumn(PLANS.CODE))
                            .where(ACCOUNTS.ACTIVE.eq(true))
                            .orderBy(ACCOUNTS.ID.asc(), PLANS.NAME.asc())
                            .fetch();
                    """;
            case LEFT_TWO_JOIN_NULL_EXTENSION -> """
                    select(ACCOUNTS.ID, PLAN_FAMILIES.LABEL)
                            .from(ACCOUNTS)
                            .leftJoin(PLANS).on(ACCOUNTS.PLAN_CODE.eqColumn(PLANS.CODE))
                            .leftJoin(PLAN_FAMILIES).on(PLANS.FAMILY_CODE.eqColumn(PLAN_FAMILIES.CODE))
                            .where(ACCOUNTS.ACTIVE.eq(true))
                            .orderBy(ACCOUNTS.ID.asc())
                            .fetch();
                    """;
            case LEFT_TWO_JOIN_SECOND_HOP_FILTER -> """
                    select(ACCOUNTS.ID, PLAN_FAMILIES.LABEL)
                            .from(ACCOUNTS)
                            .leftJoin(PLANS).on(ACCOUNTS.PLAN_CODE.eqColumn(PLANS.CODE))
                            .leftJoin(PLAN_FAMILIES).on(PLANS.FAMILY_CODE.eqColumn(PLAN_FAMILIES.CODE)
                                    .and(PLAN_FAMILIES.LABEL.eq("Growth")))
                            .where(ACCOUNTS.ACTIVE.eq(true))
                            .orderBy(ACCOUNTS.ID.asc())
                            .fetch();
                    """;
            case LEFT_TWO_JOIN_SECOND_HOP_WHERE_COLLAPSES_NULL_EXTENSION -> """
                    select(ACCOUNTS.ID, PLAN_FAMILIES.LABEL)
                            .from(ACCOUNTS)
                            .leftJoin(PLANS).on(ACCOUNTS.PLAN_CODE.eqColumn(PLANS.CODE))
                            .leftJoin(PLAN_FAMILIES).on(PLANS.FAMILY_CODE.eqColumn(PLAN_FAMILIES.CODE))
                            .where(ACCOUNTS.ACTIVE.eq(true)
                                    .and(PLAN_FAMILIES.LABEL.eq("Growth")))
                            .orderBy(ACCOUNTS.ID.asc())
                            .fetch();
                    """;
        };

        return """
                import titan.dsl.*;
                import static titan.dsl.DSL.*;

                class JoinGenerated {
                    static final AccountsTable ACCOUNTS = new AccountsTable();
                    static final PlansTable PLANS = new PlansTable();
                    static final PlanFamiliesTable PLAN_FAMILIES = new PlanFamiliesTable();

                    @StoredProcedure
                    public static void run() {
                        %s
                    }

                    static final class AccountsTable extends Table<Object> {
                        final Column<Integer> ID = column("id", SQLType.INTEGER, Nullability.NOT_NULL);
                        final Column<String> EMAIL = column("email", SQLType.TEXT, Nullability.NULLABLE);
                        final Column<Boolean> ACTIVE = column("active", SQLType.BOOLEAN, Nullability.NULLABLE);
                        final Column<String> PLAN_CODE = column("plan_code", SQLType.TEXT, Nullability.NULLABLE);
                        final Column<Integer> LOGIN_COUNT = column("login_count", SQLType.INTEGER, Nullability.NULLABLE);

                        AccountsTable() {
                            super("accounts", "public");
                        }
                    }

                    static final class PlansTable extends Table<Object> {
                        final Column<String> CODE = column("code", SQLType.TEXT, Nullability.NOT_NULL);
                        final Column<String> NAME = column("name", SQLType.TEXT, Nullability.NOT_NULL);
                        final Column<Boolean> PAID = column("paid", SQLType.BOOLEAN, Nullability.NOT_NULL);
                        final Column<String> FAMILY_CODE = column("family_code", SQLType.TEXT, Nullability.NOT_NULL);

                        PlansTable() {
                            super("plans", "public");
                        }
                    }

                    static final class PlanFamiliesTable extends Table<Object> {
                        final Column<String> CODE = column("code", SQLType.TEXT, Nullability.NOT_NULL);
                        final Column<String> LABEL = column("label", SQLType.TEXT, Nullability.NOT_NULL);

                        PlanFamiliesTable() {
                            super("plan_families", "public");
                        }
                    }
                }
                """.formatted(query);
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
