package io.titan.transpiler.tir.generative.differential;

import io.titan.transpiler.tir.generative.shared.SelectPostgresSqlHarness;
import io.titan.transpiler.tir.generative.shared.SelectCaseModel;
import io.titan.transpiler.tir.generative.shared.FixtureCatalog;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.PostgreSQLContainer;

// Docker-dependent (Testcontainers); runs via the integrationTest task, excluded from plain test (plan 4.5).
@org.junit.jupiter.api.Tag("docker")
class SelectDifferentialPostgresSqlPathIT {

    static final PostgreSQLContainer<?> POSTGRES = io.titan.test.TestContainers.postgres();

    @TempDir
    Path tempDir;

    private final SelectPostgresSqlHarness sqlHarness = new SelectPostgresSqlHarness();

    @Test
    void postgresSqlPathMatchesReferenceEvaluatorForBasicFilterProjection() throws Exception {
        SelectCaseModel.SelectCase selectCase = SelectCaseModel.selectCase(
                "transpiler-diff-basic-select",
                SelectCaseModel.SourceTable.ACCOUNTS_FIXTURE,
                List.of(
                        SelectCaseModel.projection("id", SelectCaseModel.column("id", SelectCaseModel.ValueType.INTEGER)),
                        SelectCaseModel.projection("email", SelectCaseModel.column("email", SelectCaseModel.ValueType.TEXT))),
                SelectCaseModel.and(
                        SelectCaseModel.compare(
                                SelectCaseModel.column("active", SelectCaseModel.ValueType.BOOLEAN),
                                SelectCaseModel.ComparisonOperator.EQ,
                                SelectCaseModel.boolLiteral(true)),
                        SelectCaseModel.nullCheck(
                                SelectCaseModel.column("email", SelectCaseModel.ValueType.TEXT),
                                SelectCaseModel.NullCheckKind.IS_NOT_NULL)),
                List.of(SelectCaseModel.orderBy(
                        "id",
                        SelectCaseModel.ValueType.INTEGER,
                        SelectCaseModel.SortDirection.ASC)),
                SelectCaseModel.ResultShape.ROW_SET);

        assertSqlPathMatchesReference(selectCase);
    }

    @Test
    void postgresSqlPathMatchesReferenceEvaluatorForNullChecksAndDisjunction() throws Exception {
        SelectCaseModel.SelectCase selectCase = SelectCaseModel.selectCase(
                "transpiler-diff-basic-select",
                SelectCaseModel.SourceTable.ACCOUNTS_FIXTURE,
                List.of(
                        SelectCaseModel.projection("id", SelectCaseModel.column("id", SelectCaseModel.ValueType.INTEGER)),
                        SelectCaseModel.projection("plan_code", SelectCaseModel.column("plan_code", SelectCaseModel.ValueType.TEXT))),
                SelectCaseModel.or(
                        SelectCaseModel.nullCheck(
                                SelectCaseModel.column("plan_code", SelectCaseModel.ValueType.TEXT),
                                SelectCaseModel.NullCheckKind.IS_NULL),
                        SelectCaseModel.compare(
                                SelectCaseModel.column("id", SelectCaseModel.ValueType.INTEGER),
                                SelectCaseModel.ComparisonOperator.EQ,
                                SelectCaseModel.intLiteral(2))),
                List.of(),
                SelectCaseModel.ResultShape.ROW_SET);

        assertSqlPathMatchesReference(selectCase);
    }

    @Test
    void postgresSqlPathMatchesReferenceEvaluatorForOrderedInequalityCase() throws Exception {
        SelectCaseModel.SelectCase selectCase = SelectCaseModel.selectCase(
                "transpiler-diff-basic-select",
                SelectCaseModel.SourceTable.ACCOUNTS_FIXTURE,
                List.of(
                        SelectCaseModel.projection("id", SelectCaseModel.column("id", SelectCaseModel.ValueType.INTEGER)),
                        SelectCaseModel.projection("login_count", SelectCaseModel.column("login_count", SelectCaseModel.ValueType.INTEGER))),
                SelectCaseModel.and(
                        SelectCaseModel.compare(
                                SelectCaseModel.column("id", SelectCaseModel.ValueType.INTEGER),
                                SelectCaseModel.ComparisonOperator.GT,
                                SelectCaseModel.intLiteral(2)),
                        SelectCaseModel.compare(
                                SelectCaseModel.column("id", SelectCaseModel.ValueType.INTEGER),
                                SelectCaseModel.ComparisonOperator.LE,
                                SelectCaseModel.intLiteral(5))),
                List.of(SelectCaseModel.orderBy(
                        "id",
                        SelectCaseModel.ValueType.INTEGER,
                        SelectCaseModel.SortDirection.ASC)),
                SelectCaseModel.ResultShape.ROW_SET);

        assertSqlPathMatchesReference(selectCase);
    }

    @Test
    void postgresSqlPathMatchesReferenceEvaluatorForNullableInequalityCase() throws Exception {
        SelectCaseModel.SelectCase selectCase = SelectCaseModel.selectCase(
                "transpiler-diff-basic-select",
                SelectCaseModel.SourceTable.ACCOUNTS_FIXTURE,
                List.of(
                        SelectCaseModel.projection("id", SelectCaseModel.column("id", SelectCaseModel.ValueType.INTEGER)),
                        SelectCaseModel.projection("login_count", SelectCaseModel.column("login_count", SelectCaseModel.ValueType.INTEGER))),
                SelectCaseModel.and(
                        SelectCaseModel.nullCheck(
                                SelectCaseModel.column("login_count", SelectCaseModel.ValueType.INTEGER),
                                SelectCaseModel.NullCheckKind.IS_NOT_NULL),
                        SelectCaseModel.compare(
                                SelectCaseModel.column("login_count", SelectCaseModel.ValueType.INTEGER),
                                SelectCaseModel.ComparisonOperator.LT,
                                SelectCaseModel.intLiteral(10))),
                List.of(SelectCaseModel.orderBy(
                        "login_count",
                        SelectCaseModel.ValueType.INTEGER,
                        SelectCaseModel.SortDirection.ASC)),
                SelectCaseModel.ResultShape.ROW_SET);

        assertSqlPathMatchesReference(selectCase);
    }

    @Test
    void postgresSqlPathMatchesReferenceEvaluatorForTextNotEqualCase() throws Exception {
        SelectCaseModel.SelectCase selectCase = SelectCaseModel.selectCase(
                "transpiler-diff-basic-select",
                SelectCaseModel.SourceTable.ACCOUNTS_FIXTURE,
                List.of(
                        SelectCaseModel.projection("id", SelectCaseModel.column("id", SelectCaseModel.ValueType.INTEGER)),
                        SelectCaseModel.projection("plan_code", SelectCaseModel.column("plan_code", SelectCaseModel.ValueType.TEXT))),
                SelectCaseModel.and(
                        SelectCaseModel.nullCheck(
                                SelectCaseModel.column("plan_code", SelectCaseModel.ValueType.TEXT),
                                SelectCaseModel.NullCheckKind.IS_NOT_NULL),
                        SelectCaseModel.compare(
                                SelectCaseModel.column("plan_code", SelectCaseModel.ValueType.TEXT),
                                SelectCaseModel.ComparisonOperator.NE,
                                SelectCaseModel.textLiteral("free"))),
                List.of(SelectCaseModel.orderBy(
                        "id",
                        SelectCaseModel.ValueType.INTEGER,
                        SelectCaseModel.SortDirection.ASC)),
                SelectCaseModel.ResultShape.ROW_SET);

        assertSqlPathMatchesReference(selectCase);
    }

    @Test
    void postgresSqlPathMatchesReferenceEvaluatorForNotPredicateCase() throws Exception {
        SelectCaseModel.SelectCase selectCase = SelectCaseModel.selectCase(
                "transpiler-diff-basic-select",
                SelectCaseModel.SourceTable.ACCOUNTS_FIXTURE,
                List.of(
                        SelectCaseModel.projection("id", SelectCaseModel.column("id", SelectCaseModel.ValueType.INTEGER)),
                        SelectCaseModel.projection("active", SelectCaseModel.column("active", SelectCaseModel.ValueType.BOOLEAN))),
                SelectCaseModel.not(
                        SelectCaseModel.compare(
                                SelectCaseModel.column("active", SelectCaseModel.ValueType.BOOLEAN),
                                SelectCaseModel.ComparisonOperator.EQ,
                                SelectCaseModel.boolLiteral(true))),
                List.of(SelectCaseModel.orderBy(
                        "id",
                        SelectCaseModel.ValueType.INTEGER,
                        SelectCaseModel.SortDirection.ASC)),
                SelectCaseModel.ResultShape.ROW_SET);

        assertSqlPathMatchesReference(selectCase);
    }

    @Test
    void postgresSqlPathMatchesReferenceEvaluatorForArithmeticProjectionCase() throws Exception {
        SelectCaseModel.SelectCase selectCase = SelectCaseModel.selectCase(
                "transpiler-diff-basic-select",
                SelectCaseModel.SourceTable.ACCOUNTS_FIXTURE,
                List.of(
                        SelectCaseModel.projection("id", SelectCaseModel.column("id", SelectCaseModel.ValueType.INTEGER)),
                        SelectCaseModel.projection(
                                "login_plus_one",
                                SelectCaseModel.add(
                                        SelectCaseModel.column("login_count", SelectCaseModel.ValueType.INTEGER),
                                        SelectCaseModel.intLiteral(1)))),
                SelectCaseModel.and(
                        SelectCaseModel.nullCheck(
                                SelectCaseModel.column("login_count", SelectCaseModel.ValueType.INTEGER),
                                SelectCaseModel.NullCheckKind.IS_NOT_NULL),
                        SelectCaseModel.compare(
                                SelectCaseModel.column("id", SelectCaseModel.ValueType.INTEGER),
                                SelectCaseModel.ComparisonOperator.GT,
                                SelectCaseModel.intLiteral(1))),
                List.of(SelectCaseModel.orderBy(
                        "id",
                        SelectCaseModel.ValueType.INTEGER,
                        SelectCaseModel.SortDirection.ASC)),
                SelectCaseModel.ResultShape.ROW_SET);

        assertSqlPathMatchesReference(selectCase);
    }

    @Test
    void postgresSqlPathMatchesReferenceEvaluatorForSubtractionProjectionCase() throws Exception {
        SelectCaseModel.SelectCase selectCase = SelectCaseModel.selectCase(
                "transpiler-diff-basic-select",
                SelectCaseModel.SourceTable.ACCOUNTS_FIXTURE,
                List.of(
                        SelectCaseModel.projection("id", SelectCaseModel.column("id", SelectCaseModel.ValueType.INTEGER)),
                        SelectCaseModel.projection(
                                "login_minus_one",
                                SelectCaseModel.subtract(
                                        SelectCaseModel.column("login_count", SelectCaseModel.ValueType.INTEGER),
                                        SelectCaseModel.intLiteral(1)))),
                SelectCaseModel.and(
                        SelectCaseModel.nullCheck(
                                SelectCaseModel.column("login_count", SelectCaseModel.ValueType.INTEGER),
                                SelectCaseModel.NullCheckKind.IS_NOT_NULL),
                        SelectCaseModel.compare(
                                SelectCaseModel.column("id", SelectCaseModel.ValueType.INTEGER),
                                SelectCaseModel.ComparisonOperator.GT,
                                SelectCaseModel.intLiteral(1))),
                List.of(SelectCaseModel.orderBy(
                        "id",
                        SelectCaseModel.ValueType.INTEGER,
                        SelectCaseModel.SortDirection.ASC)),
                SelectCaseModel.ResultShape.ROW_SET);

        assertSqlPathMatchesReference(selectCase);
    }

    @Test
    void postgresSqlPathMatchesReferenceEvaluatorForMultiplicationProjectionCase() throws Exception {
        SelectCaseModel.SelectCase selectCase = SelectCaseModel.selectCase(
                "transpiler-diff-basic-select",
                SelectCaseModel.SourceTable.ACCOUNTS_FIXTURE,
                List.of(
                        SelectCaseModel.projection("id", SelectCaseModel.column("id", SelectCaseModel.ValueType.INTEGER)),
                        SelectCaseModel.projection(
                                "login_times_two",
                                SelectCaseModel.multiply(
                                        SelectCaseModel.column("login_count", SelectCaseModel.ValueType.INTEGER),
                                        SelectCaseModel.intLiteral(2)))),
                SelectCaseModel.and(
                        SelectCaseModel.nullCheck(
                                SelectCaseModel.column("login_count", SelectCaseModel.ValueType.INTEGER),
                                SelectCaseModel.NullCheckKind.IS_NOT_NULL),
                        SelectCaseModel.compare(
                                SelectCaseModel.column("id", SelectCaseModel.ValueType.INTEGER),
                                SelectCaseModel.ComparisonOperator.GT,
                                SelectCaseModel.intLiteral(1))),
                List.of(SelectCaseModel.orderBy(
                        "id",
                        SelectCaseModel.ValueType.INTEGER,
                        SelectCaseModel.SortDirection.ASC)),
                SelectCaseModel.ResultShape.ROW_SET);

        assertSqlPathMatchesReference(selectCase);
    }

    @Test
    void postgresSqlPathMatchesReferenceEvaluatorForNullableArithmeticAddCase() throws Exception {
        SelectCaseModel.SelectCase selectCase = SelectCaseModel.selectCase(
                "transpiler-diff-basic-select",
                SelectCaseModel.SourceTable.ACCOUNTS_FIXTURE,
                List.of(
                        SelectCaseModel.projection("id", SelectCaseModel.column("id", SelectCaseModel.ValueType.INTEGER)),
                        SelectCaseModel.projection(
                                "login_plus_one",
                                SelectCaseModel.add(
                                        SelectCaseModel.column("login_count", SelectCaseModel.ValueType.INTEGER),
                                        SelectCaseModel.intLiteral(1)))),
                SelectCaseModel.compare(
                        SelectCaseModel.column("id", SelectCaseModel.ValueType.INTEGER),
                        SelectCaseModel.ComparisonOperator.GE,
                        SelectCaseModel.intLiteral(4)),
                List.of(SelectCaseModel.orderBy(
                        "id",
                        SelectCaseModel.ValueType.INTEGER,
                        SelectCaseModel.SortDirection.ASC)),
                SelectCaseModel.ResultShape.ROW_SET);

        assertSqlPathMatchesReference(selectCase);
    }

    @Test
    void postgresSqlPathMatchesReferenceEvaluatorForNullableArithmeticMultiplyCase() throws Exception {
        SelectCaseModel.SelectCase selectCase = SelectCaseModel.selectCase(
                "transpiler-diff-basic-select",
                SelectCaseModel.SourceTable.ACCOUNTS_FIXTURE,
                List.of(
                        SelectCaseModel.projection("id", SelectCaseModel.column("id", SelectCaseModel.ValueType.INTEGER)),
                        SelectCaseModel.projection(
                                "login_times_two",
                                SelectCaseModel.multiply(
                                        SelectCaseModel.column("login_count", SelectCaseModel.ValueType.INTEGER),
                                        SelectCaseModel.intLiteral(2)))),
                SelectCaseModel.compare(
                        SelectCaseModel.column("id", SelectCaseModel.ValueType.INTEGER),
                        SelectCaseModel.ComparisonOperator.GE,
                        SelectCaseModel.intLiteral(4)),
                List.of(SelectCaseModel.orderBy(
                        "id",
                        SelectCaseModel.ValueType.INTEGER,
                        SelectCaseModel.SortDirection.ASC)),
                SelectCaseModel.ResultShape.ROW_SET);

        assertSqlPathMatchesReference(selectCase);
    }

    private void assertSqlPathMatchesReference(SelectCaseModel.SelectCase selectCase) throws Exception {
        FixtureCatalog.FixtureTable fixture = FixtureCatalog.accountsFixture();

        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(),
                POSTGRES.getPassword())) {
            SelectPostgresSqlHarness.SelectRun run = sqlHarness.runSelectCase(
                    connection,
                    selectCase,
                    fixture,
                    tempDir);

            assertTrue(run.sql().contains("SELECT"), run.sql());
            assertTrue(run.sql().contains("ACCOUNTS") || run.sql().contains("accounts"), run.sql());
            assertEquals(run.expected().toStableJson(), run.actual().toStableJson(), "SQL-path result mismatch for SQL: " + run.sql());
            assertEquals(null, run.mismatchSummary());
            assertTrue(java.nio.file.Files.exists(run.artifactDir().resolve("case.json")));
            assertTrue(java.nio.file.Files.exists(run.artifactDir().resolve("fixture.json")));
            assertTrue(java.nio.file.Files.exists(run.artifactDir().resolve("generated-case.java")));
            assertTrue(java.nio.file.Files.exists(run.artifactDir().resolve("postgres-select.sql")));
            assertTrue(java.nio.file.Files.exists(run.artifactDir().resolve("reference-result.json")));
            assertTrue(java.nio.file.Files.exists(run.artifactDir().resolve("sql-result.json")));
            assertTrue(java.nio.file.Files.exists(run.artifactDir().resolve("repro.txt")));
        }
    }
}
