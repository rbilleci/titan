package io.titan.transpiler.tir;

import static io.titan.transpiler.tir.SemanticJsonAssertions.assertJsonTextSemanticallyEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.titan.test.TestContainers;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.PostgreSQLContainer;

// Docker-dependent (Testcontainers); runs via the integrationTest task, excluded from plain test (plan 4.5).
@org.junit.jupiter.api.Tag("docker")
class StructuredPayloadSqlModeEquivalenceIT {
    static final PostgreSQLContainer<?> POSTGRES = TestContainers.postgres();

    static final MySQLContainer<?> MYSQL = TestContainers.mysql();

    private final StructuredOutputRenderer renderer = new StructuredOutputRenderer();

    @Test
    void successEnvelopePayloadsCompareByParsedSemanticsAcrossPostgresAndMySql() throws Exception {
        StructuredOutputPlan.ObjectValue data = new StructuredOutputPlan.ObjectValue(List.of(
                entry("id", new StructuredOutputPlan.IntegerLiteralValue(10)),
                entry("title", new StructuredOutputPlan.TextLiteralValue("Operations")),
                entry("active", new StructuredOutputPlan.BooleanLiteralValue(true)),
                entry("notes", new StructuredOutputPlan.JsonNullValue()),
                entry("scores", new StructuredOutputPlan.ArrayLiteralValue(List.of(
                        new StructuredOutputPlan.IntegerLiteralValue(1),
                        new StructuredOutputPlan.IntegerLiteralValue(2))))));
        StructuredOutputPlan output = StructuredPayloadEnvelopePlan.success(data).toStructuredOutputPlan();
        Map<String, Object> expected = row(
                "data", row(
                        "id", 10,
                        "title", "Operations",
                        "active", true,
                        "notes", null,
                        "scores", List.of(1, 2)),
                "errors", List.of());

        assertRenderedPayloadMatchesBothDialects(expected, output);
    }

    @Test
    void errorEnvelopePayloadsCompareByParsedSemanticsAcrossPostgresAndMySql() throws Exception {
        StructuredPayloadErrorPlan error = new StructuredPayloadErrorPlan(
                new StructuredOutputPlan.TextLiteralValue("VALIDATION_FAILED"),
                new StructuredOutputPlan.TextLiteralValue("title 'must' use C:\\drafts before \"publish\""),
                Optional.of(new StructuredOutputPlan.ArrayLiteralValue(List.of(
                        new StructuredOutputPlan.TextLiteralValue("payload"),
                        new StructuredOutputPlan.TextLiteralValue("title")))),
                Optional.of(new StructuredOutputPlan.ObjectValue(List.of(
                        entry("line", new StructuredOutputPlan.IntegerLiteralValue(12)),
                        entry("column", new StructuredOutputPlan.IntegerLiteralValue(5))))),
                Optional.of(new StructuredOutputPlan.ObjectValue(List.of(
                        entry("rule", new StructuredOutputPlan.TextLiteralValue("required"))))),
                List.of(
                        StructuredPayloadErrorPlan.Field.CODE,
                        StructuredPayloadErrorPlan.Field.MESSAGE,
                        StructuredPayloadErrorPlan.Field.PATH,
                        StructuredPayloadErrorPlan.Field.LOCATION,
                        StructuredPayloadErrorPlan.Field.METADATA));
        StructuredOutputPlan output = StructuredPayloadEnvelopePlan.failureFromErrors(List.of(error))
                .toStructuredOutputPlan();
        Map<String, Object> expected = row(
                "data", null,
                "errors", List.of(row(
                        "code", "VALIDATION_FAILED",
                        "message", "title 'must' use C:\\drafts before \"publish\"",
                        "path", List.of("payload", "title"),
                        "location", row("line", 12, "column", 5),
                        "metadata", row("rule", "required"))));

        assertRenderedPayloadMatchesBothDialects(expected, output);
    }

    private void assertRenderedPayloadMatchesBothDialects(
            Map<String, Object> expected,
            StructuredOutputPlan output
    ) throws SQLException {
        try (Connection postgres = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Connection mysql = DriverManager.getConnection(
                     MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())) {
            String postgresJson = selectJson(postgres, renderer.renderPostgreSql(output));
            String mysqlJson = selectJson(mysql, renderer.renderMySql(output));

            assertJsonTextSemanticallyEquals(expected, postgresJson);
            assertJsonTextSemanticallyEquals(expected, mysqlJson);
            assertJsonTextSemanticallyEquals(postgresJson, mysqlJson);
        }
    }

    private static String selectJson(Connection connection, String expression) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT " + expression)) {
            assertTrue(resultSet.next());
            Object value = resultSet.getObject(1);
            return value.toString();
        }
    }

    private static StructuredOutputPlan.Entry entry(String key, StructuredOutputPlan.Value value) {
        return new StructuredOutputPlan.Entry(StructuredOutputPlan.OutputKey.compilerKnown(key), value);
    }

    private static LinkedHashMap<String, Object> row(Object... pairs) {
        if (pairs.length % 2 != 0) {
            throw new IllegalArgumentException("row requires key/value pairs");
        }
        LinkedHashMap<String, Object> row = new LinkedHashMap<>();
        for (int index = 0; index < pairs.length; index += 2) {
            row.put((String) pairs[index], pairs[index + 1]);
        }
        return row;
    }
}
