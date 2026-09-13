package io.titan.transpiler.tir.generative.shared;

import io.titan.transpiler.tir.generative.shared.SelectCaseModel;
import io.titan.transpiler.tir.generative.shared.ReferenceResultComparison;
import io.titan.transpiler.tir.generative.shared.ReferenceResultModels.ResultRow;
import io.titan.transpiler.tir.generative.shared.ReferenceResultModels.ReferenceResult;
import io.titan.transpiler.tir.generative.shared.FixtureCatalog;
import io.titan.transpiler.tir.generative.shared.FailureTriageSupport;
import io.titan.transpiler.tir.generative.shared.ArtifactSummaryWriter;
import io.titan.transpiler.tir.LoweredSelectSqlEmitter;
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

public final class SelectPostgresSqlHarness {

    public record SelectRun(
            String generatedJavaSource,
            String sql,
            ReferenceResult expected,
            ReferenceResult actual,
            String mismatchSummary,
            Path artifactDir
    ) {
    }

    public String emitPostgresSelectSql(SelectCaseModel.SelectCase selectCase, Path tempDir) throws Exception {
        Objects.requireNonNull(selectCase, "selectCase");
        Objects.requireNonNull(tempDir, "tempDir");

        return new LoweredSelectSqlEmitter().emitPostgresSelectSql(renderJavaSource(selectCase), tempDir);
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

        Path artifactDir = Files.createDirectories(artifactRoot.resolve(selectCase.profileId() + "-artifacts"));
        String generatedJavaSource = renderJavaSource(selectCase);
        Files.writeString(artifactDir.resolve("generated-case.java"), generatedJavaSource);
        Files.writeString(artifactDir.resolve("case.json"), selectCase.toStableJson());
        Files.writeString(artifactDir.resolve("fixture.json"), fixtureTable.toStableJson());

        String sql = new LoweredSelectSqlEmitter().emitPostgresSelectSql(generatedJavaSource, artifactDir);
        installFixture(connection, fixtureTable);
        ReferenceResult expected = new SelectReferenceEvaluator().evaluate(selectCase, fixtureTable);
        ReferenceResult actual = executeSelect(connection, selectCase, sql);
        String mismatchSummary = new ReferenceResultComparison().summarizeMismatch(expected, actual);

        writeArtifacts(artifactDir, sql, expected, actual, mismatchSummary, selectCase);
        return new SelectRun(generatedJavaSource, sql, expected, actual, mismatchSummary, artifactDir);
    }

    public void installFixture(Connection connection, FixtureCatalog.FixtureTable fixtureTable) throws Exception {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(fixtureTable, "fixtureTable");

        try (Statement stmt = connection.createStatement()) {
            stmt.execute("DROP TABLE IF EXISTS public.accounts_fixture");
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
            for (FixtureCatalog.FixtureRow row : fixtureTable.rows()) {
                stmt.execute("INSERT INTO public.accounts (id, email, active, plan_code, login_count) VALUES ("
                        + sqlLiteral(row.values().get("id")) + ", "
                        + sqlLiteral(row.values().get("email")) + ", "
                        + sqlLiteral(row.values().get("active")) + ", "
                        + sqlLiteral(row.values().get("plan_code")) + ", "
                        + sqlLiteral(row.values().get("login_count")) + ")");
            }
        }
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
                    values.put(projection.alias(), value);
                }
                rows.add(new ResultRow(values));
            }
        }
        return new ReferenceResult(List.copyOf(rows));
    }

    static String renderJavaSource(SelectCaseModel.SelectCase selectCase) {
        String projections = selectCase.projections().stream()
                .map(projection -> renderExpression(projection.expression()))
                .reduce((left, right) -> left + ", " + right)
                .orElseThrow();

        StringBuilder query = new StringBuilder("select(" + projections + ").from(ACCOUNTS)");
        if (selectCase.filter() != null) {
            query.append(".where(").append(renderPredicate(selectCase.filter())).append(")");
        }
        if (!selectCase.ordering().isEmpty()) {
            String ordering = selectCase.ordering().stream()
                    .map(io.titan.transpiler.tir.generative.shared.SelectPostgresSqlHarness::renderOrdering)
                    .reduce((left, right) -> left + ", " + right)
                    .orElseThrow();
            query.append(".orderBy(").append(ordering).append(")");
        }
        query.append(".fetch();");

        return """
                import titan.dsl.*;
                import static titan.dsl.DSL.*;

                class SelectGenerated {
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

    private static String renderExpression(SelectCaseModel.Expression expression) {
        return switch (expression) {
            case SelectCaseModel.ColumnRef column -> renderColumn(column.columnName());
            case SelectCaseModel.IntLiteral literal -> Integer.toString(literal.value());
            case SelectCaseModel.BooleanLiteral literal -> Boolean.toString(literal.value());
            case SelectCaseModel.TextLiteral literal -> "\"" + literal.value().replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
            case SelectCaseModel.ArithmeticExpression arithmetic -> renderArithmetic(arithmetic);
        };
    }

    private static String renderArithmetic(SelectCaseModel.ArithmeticExpression arithmetic) {
        String left = renderExpression(arithmetic.left());
        String right = renderExpression(arithmetic.right());
        return switch (arithmetic.operator()) {
            case "+" -> left + ".add(" + right + ")";
            case "-" -> left + ".subtract(" + right + ")";
            case "*" -> left + ".multiply(" + right + ")";
            default -> throw new IllegalArgumentException(
                    "Select Postgres SQL harness does not yet support arithmetic operator: " + arithmetic.operator());
        };
    }

    private static String renderPredicate(SelectCaseModel.Predicate predicate) {
        return switch (predicate) {
            case SelectCaseModel.ComparisonPredicate comparison -> renderComparison(comparison);
            case SelectCaseModel.NullCheckPredicate nullCheck -> renderNullCheck(nullCheck);
            case SelectCaseModel.LogicalPredicate logical -> renderLogical(logical);
            case SelectCaseModel.NotPredicate not -> renderPredicate(not.predicate()) + ".not()";
        };
    }

    private static String renderComparison(SelectCaseModel.ComparisonPredicate comparison) {
        if (!(comparison.left() instanceof SelectCaseModel.ColumnRef column)) {
            throw new IllegalArgumentException("Select Postgres SQL harness currently requires column refs on comparison left-hand side");
        }
        String receiver = renderColumn(column.columnName());
        String argument = renderExpression(comparison.right());
        return switch (comparison.operator()) {
            case EQ -> receiver + ".eq(" + argument + ")";
            case NE -> receiver + ".ne(" + argument + ")";
            case LT -> receiver + ".lt(" + argument + ")";
            case LE -> receiver + ".le(" + argument + ")";
            case GT -> receiver + ".gt(" + argument + ")";
            case GE -> receiver + ".ge(" + argument + ")";
        };
    }

    private static String renderNullCheck(SelectCaseModel.NullCheckPredicate nullCheck) {
        if (!(nullCheck.expression() instanceof SelectCaseModel.ColumnRef column)) {
            throw new IllegalArgumentException("Select Postgres SQL harness currently requires column refs for null checks");
        }
        String receiver = renderColumn(column.columnName());
        return switch (nullCheck.kind()) {
            case IS_NULL -> receiver + ".isNull()";
            case IS_NOT_NULL -> receiver + ".isNotNull()";
        };
    }

    private static String renderLogical(SelectCaseModel.LogicalPredicate logical) {
        String rendered = renderPredicate(logical.predicates().getFirst());
        for (int i = 1; i < logical.predicates().size(); i++) {
            String right = renderPredicate(logical.predicates().get(i));
            rendered = switch (logical.operator()) {
                case AND -> rendered + ".and(" + right + ")";
                case OR -> rendered + ".or(" + right + ")";
            };
        }
        return rendered;
    }

    private static String renderOrdering(SelectCaseModel.Ordering ordering) {
        String rendered = renderColumn(ordering.column().columnName())
                + (ordering.direction() == SelectCaseModel.SortDirection.ASC ? ".asc()" : ".desc()");
        return switch (ordering.nulls()) {
            case DIALECT_DEFAULT -> rendered;
            case FIRST -> rendered + ".nullsFirst()";
            case LAST -> rendered + ".nullsLast()";
        };
    }

    private static String renderColumn(String columnName) {
        return switch (columnName) {
            case "id" -> "ACCOUNTS.ID";
            case "email" -> "ACCOUNTS.EMAIL";
            case "active" -> "ACCOUNTS.ACTIVE";
            case "plan_code" -> "ACCOUNTS.PLAN_CODE";
            case "login_count" -> "ACCOUNTS.LOGIN_COUNT";
            default -> throw new IllegalArgumentException("Unsupported fixture column: " + columnName);
        };
    }

    private static void writeArtifacts(
            Path artifactDir,
            String sql,
            ReferenceResult expected,
            ReferenceResult actual,
            String mismatchSummary,
            SelectCaseModel.SelectCase selectCase
    ) throws IOException {
        Files.writeString(artifactDir.resolve("postgres-select.sql"), sql + System.lineSeparator());
        Files.writeString(artifactDir.resolve("reference-result.json"), expected.toStableJson() + System.lineSeparator());
        Files.writeString(artifactDir.resolve("sql-result.json"), actual.toStableJson() + System.lineSeparator());
        Files.writeString(artifactDir.resolve("repro.txt"), """
                Replay this bounded select case with:

                source ~/.sdkman/bin/sdkman-init.sh && ./gradlew :titan-transpiler:test \
                  --tests io.titan.transpiler.tir.generative.differential.SelectDifferentialPostgresSqlPathIT \
                  --no-daemon

                Profile: %s
                """.formatted(selectCase.profileId()));
        if (mismatchSummary != null) {
            Files.writeString(artifactDir.resolve("mismatch.txt"), mismatchSummary + System.lineSeparator());
        }
        FailureTriageSupport.Triage triage = FailureTriageSupport.forMismatch(mismatchSummary);
        ArtifactSummaryWriter.write(
                artifactDir,
                "Select SQL artifact summary",
                mismatchSummary == null ? "green" : "mismatch",
                Map.of(
                        "Profile", selectCase.profileId(),
                        "Failure bucket", triage.bucket(),
                        "Likely layer hint", triage.likelyLayerHint(),
                        "Case", selectCase.toStableJson()),
                mismatchSummary == null
                        ? List.of("generated-case.java", "postgres-select.sql", "reference-result.json", "sql-result.json", "repro.txt")
                        : List.of("generated-case.java", "postgres-select.sql", "reference-result.json", "sql-result.json", "mismatch.txt", "repro.txt"));
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
