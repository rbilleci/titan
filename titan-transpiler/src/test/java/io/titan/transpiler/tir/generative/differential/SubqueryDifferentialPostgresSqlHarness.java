package io.titan.transpiler.tir.generative.differential;

import io.titan.transpiler.tir.generative.shared.SelectPostgresSqlHarness;
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
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

final class SubqueryDifferentialPostgresSqlHarness {

    record Run(
            String generatedJavaSource,
            String sql,
            ReferenceResult expected,
            ReferenceResult actual,
            String mismatchSummary,
            Path artifactDir
    ) {
    }

    Run run(
            Connection connection,
            SubqueryDifferentialCaseModel.Case subqueryCase,
            FixtureCatalog.FixtureTable fixtureTable,
            Path artifactRoot
    ) throws Exception {
        Path artifactDir = Files.createDirectories(artifactRoot.resolve(subqueryCase.family().id() + "-artifacts"));
        String source = renderJavaSource(subqueryCase);
        Files.writeString(artifactDir.resolve("case.json"), subqueryCase.toStableJson());
        Files.writeString(artifactDir.resolve("generated-case.java"), source);
        Files.writeString(artifactDir.resolve("fixture.json"), fixtureTable.toStableJson());

        String sql = new LoweredSelectSqlEmitter().emitPostgresSelectSql(source, artifactDir);
        new SelectPostgresSqlHarness().installFixture(connection, fixtureTable);
        ReferenceResult expected = new SubqueryDifferentialReferenceEvaluator().evaluate(subqueryCase, fixtureTable);
        ReferenceResult actual = execute(connection, subqueryCase, sql);
        String mismatch = new ReferenceResultComparison().summarizeMismatch(expected, actual);

        Files.writeString(artifactDir.resolve("postgres-select.sql"), sql + System.lineSeparator());
        Files.writeString(artifactDir.resolve("reference-result.json"), expected.toStableJson() + System.lineSeparator());
        Files.writeString(artifactDir.resolve("sql-result.json"), actual.toStableJson() + System.lineSeparator());
        Files.writeString(artifactDir.resolve("repro.txt"), """
                Replay this bounded subquery differential case with:

                source ~/.sdkman/bin/sdkman-init.sh && ./gradlew :titan-transpiler:test \
                  --tests io.titan.transpiler.tir.generative.differential.SubqueryDifferentialReplayTest \
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
                "Phase B subquery artifact summary",
                mismatch == null ? "green" : "mismatch",
                Map.of(
                        "Phase", "B",
                        "Profile", subqueryCase.profileId(),
                        "Failure bucket", triage.bucket(),
                        "Likely layer hint", triage.likelyLayerHint(),
                        "Family", subqueryCase.family().id()),
                mismatch == null
                        ? List.of("generated-case.java", "postgres-select.sql", "reference-result.json", "sql-result.json", "repro.txt")
                        : List.of("generated-case.java", "postgres-select.sql", "reference-result.json", "sql-result.json", "mismatch.txt", "repro.txt"));
        return new Run(source, sql, expected, actual, mismatch, artifactDir);
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

    static String renderJavaSource(SubqueryDifferentialCaseModel.Case subqueryCase) {
        String query = switch (subqueryCase.family()) {
            case EXISTS_ENTERPRISE -> """
                    select(ACCOUNTS.ID, ACCOUNTS.PLAN_CODE)
                            .from(ACCOUNTS)
                            .where(DSL.exists(
                                    select(ACCOUNTS.ID)
                                            .from(ACCOUNTS)
                                            .where(ACCOUNTS.PLAN_CODE.eq(\"enterprise\"))
                                            .limit(1)))
                            .orderBy(ACCOUNTS.ID.asc())
                            .fetch();
                    """;
            case NOT_EXISTS_VIP_ACTIVE -> """
                    select(ACCOUNTS.ID, ACCOUNTS.EMAIL)
                            .from(ACCOUNTS)
                            .where(ACCOUNTS.ACTIVE.eq(true)
                                    .and(DSL.notExists(
                                            select(ACCOUNTS.ID)
                                                    .from(ACCOUNTS)
                                                    .where(ACCOUNTS.PLAN_CODE.eq(\"vip\"))
                                                    .limit(1))))
                            .orderBy(ACCOUNTS.ID.asc())
                            .fetch();
                    """;
            case CORRELATED_EXISTS_ACTIVE_PLAN -> """
                    CommonTableExpression<Object> active_accounts = DSL.name("active_accounts")
                            .as(select(ACCOUNTS.ID, ACCOUNTS.EMAIL, ACCOUNTS.PLAN_CODE)
                                    .from(ACCOUNTS)
                                    .where(ACCOUNTS.ACTIVE.eq(true)));
                    Column<Integer> activeId = active_accounts.field("id", SQLType.INTEGER, Nullability.NOT_NULL);
                    Column<String> activeEmail = active_accounts.field("email", SQLType.TEXT, Nullability.NULLABLE);
                    Column<String> activePlanCode = active_accounts.field("plan_code", SQLType.TEXT, Nullability.NULLABLE);
                    DSL.with(active_accounts)
                            .select(activeId, activeEmail)
                            .from(active_accounts)
                            .where(DSL.exists(
                                    select(ACCOUNTS.ID)
                                            .from(ACCOUNTS)
                                            .where(ACCOUNTS.PLAN_CODE.eqColumn(activePlanCode))
                                            .limit(1)))
                            .orderBy(activeId.asc())
                            .fetch();
                    """;
            case CORRELATED_NOT_EXISTS_NULL_PLAN -> """
                    CommonTableExpression<Object> active_accounts = DSL.name("active_accounts")
                            .as(select(ACCOUNTS.ID, ACCOUNTS.EMAIL, ACCOUNTS.PLAN_CODE)
                                    .from(ACCOUNTS)
                                    .where(ACCOUNTS.ACTIVE.eq(true)));
                    Column<Integer> activeId = active_accounts.field("id", SQLType.INTEGER, Nullability.NOT_NULL);
                    Column<String> activeEmail = active_accounts.field("email", SQLType.TEXT, Nullability.NULLABLE);
                    Column<String> activePlanCode = active_accounts.field("plan_code", SQLType.TEXT, Nullability.NULLABLE);
                    DSL.with(active_accounts)
                            .select(activeId, activeEmail)
                            .from(active_accounts)
                            .where(DSL.notExists(
                                    select(ACCOUNTS.ID)
                                            .from(ACCOUNTS)
                                            .where(ACCOUNTS.PLAN_CODE.eqColumn(activePlanCode))
                                            .limit(1)))
                            .orderBy(activeId.asc())
                            .fetch();
                    """;
            case CORRELATED_SCALAR_EMAIL_BY_PLAN -> """
                    CommonTableExpression<Object> active_accounts = DSL.name("active_accounts")
                            .as(select(ACCOUNTS.ID, ACCOUNTS.EMAIL, ACCOUNTS.PLAN_CODE)
                                    .from(ACCOUNTS)
                                    .where(ACCOUNTS.ACTIVE.eq(true)));
                    Column<Integer> activeId = active_accounts.field("id", SQLType.INTEGER, Nullability.NOT_NULL);
                    Column<String> activeEmail = active_accounts.field("email", SQLType.TEXT, Nullability.NULLABLE);
                    Column<String> activePlanCode = active_accounts.field("plan_code", SQLType.TEXT, Nullability.NULLABLE);
                    DSL.with(active_accounts)
                            .select(activeId, activeEmail)
                            .from(active_accounts)
                            .where(activeEmail.eqColumn(
                                    DSL.scalar(
                                            select(ACCOUNTS.EMAIL)
                                                    .from(ACCOUNTS)
                                                    .where(ACCOUNTS.PLAN_CODE.eqColumn(activePlanCode))
                                                    .limit(1))))
                            .orderBy(activeId.asc())
                            .fetch();
                    """;
            case CORRELATED_SCALAR_NULL_OR_ABSENT_EMAIL_BY_PLAN -> """
                    CommonTableExpression<Object> active_accounts = DSL.name("active_accounts")
                            .as(select(ACCOUNTS.ID, ACCOUNTS.EMAIL, ACCOUNTS.PLAN_CODE)
                                    .from(ACCOUNTS)
                                    .where(ACCOUNTS.ACTIVE.eq(true)));
                    Column<Integer> activeId = active_accounts.field("id", SQLType.INTEGER, Nullability.NOT_NULL);
                    Column<String> activeEmail = active_accounts.field("email", SQLType.TEXT, Nullability.NULLABLE);
                    Column<String> activePlanCode = active_accounts.field("plan_code", SQLType.TEXT, Nullability.NULLABLE);
                    DSL.with(active_accounts)
                            .select(activeId, activeEmail)
                            .from(active_accounts)
                            .where(activePlanCode.isNotNull()
                                    .and(DSL.scalar(
                                            select(ACCOUNTS.EMAIL)
                                                    .from(ACCOUNTS)
                                                    .where(ACCOUNTS.PLAN_CODE.eqColumn(activePlanCode)
                                                            .and(ACCOUNTS.EMAIL.isNull()))
                                                    .limit(1)).isNull()))
                            .orderBy(activeId.asc())
                            .fetch();
                    """;
            case SCALAR_EMAIL_LOOKUP -> """
                    select(ACCOUNTS.ID, ACCOUNTS.EMAIL)
                            .from(ACCOUNTS)
                            .where(ACCOUNTS.EMAIL.eqColumn(
                                    DSL.scalar(
                                            select(ACCOUNTS.EMAIL)
                                                    .from(ACCOUNTS)
                                                    .where(ACCOUNTS.ID.eq(1))
                                                    .limit(1))))
                            .orderBy(ACCOUNTS.ID.asc())
                            .fetch();
                    """;
            case SCALAR_NULL_EMAIL_LOOKUP -> """
                    select(ACCOUNTS.ID, ACCOUNTS.EMAIL)
                            .from(ACCOUNTS)
                            .where(ACCOUNTS.EMAIL.eqColumn(
                                    DSL.scalar(
                                            select(ACCOUNTS.EMAIL)
                                                    .from(ACCOUNTS)
                                                    .where(ACCOUNTS.ID.eq(3))
                                                    .limit(1))))
                            .orderBy(ACCOUNTS.ID.asc())
                            .fetch();
                    """;
            case CTE_ACTIVE_ROWS -> """
                    CommonTableExpression<Object> active_accounts = DSL.name(\"active_accounts\")
                            .as(select(ACCOUNTS.ID, ACCOUNTS.EMAIL)
                                    .from(ACCOUNTS)
                                    .where(ACCOUNTS.ACTIVE.eq(true)));
                    Column<Integer> activeId = active_accounts.field(\"id\", SQLType.INTEGER, Nullability.NOT_NULL);
                    Column<String> activeEmail = active_accounts.field(\"email\", SQLType.TEXT, Nullability.NULLABLE);
                    DSL.with(active_accounts)
                            .select(activeId, activeEmail)
                            .from(active_accounts)
                            .orderBy(activeId.asc())
                            .fetch();
                    """;
        };

        return """
                import titan.dsl.*;
                import static titan.dsl.DSL.*;

                class SubqueryDifferentialGenerated {
                    static final AccountsTable ACCOUNTS = new AccountsTable();

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
                }
                """.formatted(query);
    }
}
