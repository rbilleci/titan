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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

final class AggregationDifferentialPostgresSqlHarness {

    record AggregationRun(
            String generatedJavaSource,
            String sql,
            ReferenceResult expected,
            ReferenceResult actual,
            String mismatchSummary,
            Path artifactDir
    ) {
    }

    AggregationRun run(
            Connection connection,
            AggregationDifferentialCaseModel.AggregationCase aggregationCase,
            FixtureCatalog.FixtureTable fixtureTable,
            Path artifactRoot
    ) throws Exception {
        Objects.requireNonNull(connection, "connection");
        Path artifactDir = Files.createDirectories(artifactRoot.resolve(aggregationCase.profileId() + "-aggregation-artifacts"));
        String source = renderJavaSource(aggregationCase);
        Files.writeString(artifactDir.resolve("case.json"), aggregationCase.toStableJson());
        Files.writeString(artifactDir.resolve("generated-case.java"), source);
        Files.writeString(artifactDir.resolve("fixture.json"), fixtureTable.toStableJson());

        String sql = new LoweredSelectSqlEmitter().emitPostgresSelectSql(source, artifactDir);
        new SelectPostgresSqlHarness().installFixture(connection, fixtureTable);
        ReferenceResult expected = new AggregationDifferentialReferenceEvaluator().evaluate(aggregationCase, fixtureTable);
        ReferenceResult actual = execute(connection, aggregationCase, sql);
        String mismatch = new ReferenceResultComparison().summarizeMismatch(expected, actual);

        Files.writeString(artifactDir.resolve("postgres-select.sql"), sql + System.lineSeparator());
        Files.writeString(artifactDir.resolve("reference-result.json"), expected.toStableJson() + System.lineSeparator());
        Files.writeString(artifactDir.resolve("sql-result.json"), actual.toStableJson() + System.lineSeparator());
        Files.writeString(artifactDir.resolve("repro.txt"), """
                Replay this bounded aggregation differential case with:

                source ~/.sdkman/bin/sdkman-init.sh && ./gradlew :titan-transpiler:test \
                  --tests io.titan.transpiler.tir.generative.differential.AggregationDifferentialReplayTest \
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
                "Phase B aggregation artifact summary",
                mismatch == null ? "green" : "mismatch",
                Map.of(
                        "Phase", "B",
                        "Profile", aggregationCase.profileId(),
                        "Failure bucket", triage.bucket(),
                        "Likely layer hint", triage.likelyLayerHint(),
                        "Aggregate", aggregationCase.aggregateKind().name(),
                        "Having", aggregationCase.havingKind().name()),
                mismatch == null
                        ? List.of("generated-case.java", "postgres-select.sql", "reference-result.json", "sql-result.json", "repro.txt")
                        : List.of("generated-case.java", "postgres-select.sql", "reference-result.json", "sql-result.json", "mismatch.txt", "repro.txt"));
        return new AggregationRun(source, sql, expected, actual, mismatch, artifactDir);
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
                values.put(aggregationCase.groupAlias(), rs.getObject(1));
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

    static String renderJavaSource(AggregationDifferentialCaseModel.AggregationCase aggregationCase) {
        String groupExpr = switch (aggregationCase.groupKey()) {
            case PLAN_CODE -> "ACCOUNTS.PLAN_CODE";
            case ACTIVE -> "ACCOUNTS.ACTIVE";
        };
        String aggregateExpr = switch (aggregationCase.aggregateKind()) {
            case COUNT_ALL -> "count()";
            case SUM_LOGIN_COUNT -> "sum(ACCOUNTS.LOGIN_COUNT)";
            case MIN_LOGIN_COUNT -> "min(ACCOUNTS.LOGIN_COUNT)";
            case MAX_LOGIN_COUNT -> "max(ACCOUNTS.LOGIN_COUNT)";
        };
        String orderExpr = switch (aggregationCase.groupKey()) {
            case PLAN_CODE -> "ACCOUNTS.PLAN_CODE.asc()";
            case ACTIVE -> "ACCOUNTS.ACTIVE.asc()";
        };
        String havingExpr = switch (aggregationCase.havingKind()) {
            case NONE -> "";
            case COUNT_GT_ONE -> ".having(count().gt(1L))";
            case SUM_GT_TEN -> ".having(sum(ACCOUNTS.LOGIN_COUNT).gt(10))";
        };
        return """
                import titan.dsl.*;
                import static titan.dsl.DSL.*;

                class AggregationDifferentialGenerated {
                    static final AccountsTable ACCOUNTS = new AccountsTable();

                    @StoredProcedure
                    public static void run() {
                        select(%s, %s)
                                .from(ACCOUNTS)
                                .groupBy(%s)%s
                                .orderBy(%s)
                                .fetch();
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
                """.formatted(groupExpr, aggregateExpr, groupExpr, havingExpr, orderExpr);
    }
}
