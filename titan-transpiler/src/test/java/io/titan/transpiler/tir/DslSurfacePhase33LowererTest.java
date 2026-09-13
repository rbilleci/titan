package io.titan.transpiler.tir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

import io.titan.transpiler.DiscoveredEntryPoint;
import io.titan.transpiler.EntryPointDiscovery;
import io.titan.transpiler.JavaSourceParser;
import io.titan.transpiler.ParsedSources;
import io.titan.transpiler.diagnostics.TitanDiagnosticException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Phase 3.3 (audit D-7) lowering: table aliases (self-joins), DISTINCT, searched CASE,
 * NULLS FIRST/LAST and NOT IN lower to structured TIR, and the new structure round-trips
 * through both emitters with quoted identifiers.
 */
class DslSurfacePhase33LowererTest {

    @TempDir
    Path tempDir;

    private static final String EMPLOYEES_TABLE = """
                static final class EmployeesTable extends Table<Object> {
                    public final Column<Integer> ID = column("id", SQLType.INTEGER, Nullability.NOT_NULL);
                    public final Column<String> NAME = column("name", SQLType.VARCHAR, Nullability.NOT_NULL);
                    public final Column<Integer> MANAGER_ID = column("manager_id", SQLType.INTEGER, Nullability.NULLABLE);
                    public final Column<Integer> SCORE = column("score", SQLType.INTEGER, Nullability.NULLABLE);

                    EmployeesTable() {
                        super("employees", "test");
                    }
                }
            """;

    private SelectSql lowerSingleSelect(String className, String body) throws Exception {
        Path sourceFile = tempDir.resolve(className + ".java");
        Files.writeString(sourceFile, """
                import titan.dsl.*;
                import static titan.dsl.DSL.*;

                class %s {
                    static final EmployeesTable EMPLOYEES = new EmployeesTable();

                    @StoredProcedure
                    public static void run() {
                %s
                    }

                %s
                }
                """.formatted(className, body, EMPLOYEES_TABLE));

        ParsedSources parsed = new JavaSourceParser().parse(List.of(sourceFile), List.of(), "21", false);
        List<DiscoveredEntryPoint> entryPoints = new EntryPointDiscovery().discover(parsed);
        var block = new JavaToTirLowerer().lower(parsed, entryPoints).values().iterator().next();
        var exec = assertInstanceOf(ExecuteSqlStatement.class, block.statements().getLast());
        return assertInstanceOf(SelectSql.class, exec.sqlNode());
    }

    @Test
    void lowersAliasedSelfJoinToStructuredAliases() throws Exception {
        SelectSql select = lowerSingleSelect("LoweringSelfJoin", """
                        AliasedTable<Object> e = EMPLOYEES.as("e");
                        AliasedTable<Object> m = EMPLOYEES.as("m");
                        select(e.col(EMPLOYEES.NAME), m.col(EMPLOYEES.NAME))
                                .from(e)
                                .join(m).on(e.col(EMPLOYEES.MANAGER_ID), m.col(EMPLOYEES.ID))
                                .fetch();
                """);

        // ATG-020: the FROM/JOIN clause carries the declared schema (super("employees", "test")); columns
        // reference the alias, so the schema never over-qualifies a column.
        assertEquals("test.employees", select.from());
        assertEquals("e", select.fromAlias());

        assertEquals(1, select.joins().size());
        JoinSpec join = select.joins().getFirst();
        assertEquals(JoinType.INNER, join.joinType());
        assertEquals("test.employees", join.target());
        assertEquals("m", join.targetAlias());

        ColumnRefExpression left = assertInstanceOf(ColumnRefExpression.class, select.columns().get(0).expression());
        assertEquals("e", left.table());
        assertEquals("name", left.column());
        ColumnRefExpression right = assertInstanceOf(ColumnRefExpression.class, select.columns().get(1).expression());
        assertEquals("m", right.table());
        assertEquals("name", right.column());

        BinaryOpExpression condition = assertInstanceOf(BinaryOpExpression.class, join.condition());
        assertEquals("e", assertInstanceOf(ColumnRefExpression.class, condition.left()).table());
        assertEquals("manager_id", assertInstanceOf(ColumnRefExpression.class, condition.left()).column());
        assertEquals("m", assertInstanceOf(ColumnRefExpression.class, condition.right()).table());
        assertEquals("id", assertInstanceOf(ColumnRefExpression.class, condition.right()).column());

        // Round-trip both emitters: alias-qualified, identifier-quoted SQL.
        assertEquals("SELECT \"e\".\"name\", \"m\".\"name\" FROM \"test\".\"employees\" AS \"e\" "
                        + "JOIN \"test\".\"employees\" AS \"m\" ON (\"e\".\"manager_id\" = \"m\".\"id\")",
                new PostgreSqlEmitter().visitSelectSql(select));
        assertEquals("SELECT `e`.`name`, `m`.`name` FROM `test`.`employees` AS `e` "
                        + "JOIN `test`.`employees` AS `m` ON (`e`.`manager_id` = `m`.`id`)",
                new MySqlEmitter().visitSelectSql(select));
    }

    @Test
    void atg020SchemaQualifiedTablePreservedInFromWithBareColumnQualifiers() throws Exception {
        // ATG-020: a schema-bearing Table (super("employees", "test")) must emit FROM "test"."employees"
        // (schema preserved), while columns keep the bare "employees" qualifier — never the invalid
        // "test"."employees"."col" that PostgreSQL rejects (the prior workaround's over-qualification).
        SelectSql select = lowerSingleSelect("LoweringSchemaQualified", """
                        select(EMPLOYEES.NAME)
                                .from(EMPLOYEES)
                                .where(EMPLOYEES.ID.eq(1))
                                .fetch();
                """);
        assertEquals("test.employees", select.from());
        assertNull(select.fromAlias());

        String pg = new PostgreSqlEmitter().visitSelectSql(select);
        assertTrue(pg.contains("FROM \"test\".\"employees\""), pg);
        assertTrue(pg.contains("\"employees\".\"name\""), pg);
        assertFalse(pg.contains("\"test\".\"employees\".\""), "columns must not be schema-over-qualified: " + pg);

        String mysql = new MySqlEmitter().visitSelectSql(select);
        assertTrue(mysql.contains("FROM `test`.`employees`"), mysql);
        assertTrue(mysql.contains("`employees`.`name`"), mysql);
        assertFalse(mysql.contains("`test`.`employees`.`"), "columns must not be schema-over-qualified: " + mysql);
    }

    @Test
    void lowersDistinctToSelectSqlFlag() throws Exception {
        SelectSql select = lowerSingleSelect("LoweringDistinct", """
                        select(EMPLOYEES.NAME)
                                .from(EMPLOYEES)
                                .distinct()
                                .fetch();
                """);

        assertTrue(select.distinct());
        assertTrue(new PostgreSqlEmitter().visitSelectSql(select).startsWith("SELECT DISTINCT \"employees\".\"name\""));
        assertTrue(new MySqlEmitter().visitSelectSql(select).startsWith("SELECT DISTINCT `employees`.`name`"));
    }

    @Test
    void lowersNotInValuesToNegatedInList() throws Exception {
        SelectSql select = lowerSingleSelect("LoweringNotIn", """
                        select(EMPLOYEES.NAME)
                                .from(EMPLOYEES)
                                .where(EMPLOYEES.ID.notIn(3, 5, 8))
                                .fetch();
                """);

        InListExpression notIn = assertInstanceOf(InListExpression.class, select.where());
        assertTrue(notIn.negated());
        assertNull(notIn.subquery());
        assertEquals(3, notIn.items().size());
        assertEquals("id", assertInstanceOf(ColumnRefExpression.class, notIn.value()).column());

        assertTrue(new PostgreSqlEmitter().visitSelectSql(select)
                .contains("WHERE \"employees\".\"id\" NOT IN (3, 5, 8)"));
        assertTrue(new MySqlEmitter().visitSelectSql(select)
                .contains("WHERE `employees`.`id` NOT IN (3, 5, 8)"));
    }

    @Test
    void lowersInAndNotInSubqueriesToInListSubqueryForm() throws Exception {
        SelectSql select = lowerSingleSelect("LoweringNotInSubquery", """
                        select(EMPLOYEES.NAME)
                                .from(EMPLOYEES)
                                .where(EMPLOYEES.ID.notIn(
                                        select(EMPLOYEES.MANAGER_ID)
                                                .from(EMPLOYEES)
                                                .where(EMPLOYEES.SCORE.lt(10))))
                                .fetch();
                """);

        InListExpression notIn = assertInstanceOf(InListExpression.class, select.where());
        assertTrue(notIn.negated());
        assertTrue(notIn.items().isEmpty());
        assertEquals("test.employees", notIn.subquery().from());

        // ATG-020: FROM carries the schema; column qualifiers stay bare ("employees", not "test"."employees").
        assertTrue(new PostgreSqlEmitter().visitSelectSql(select)
                .contains("\"employees\".\"id\" NOT IN (SELECT \"employees\".\"manager_id\" FROM \"test\".\"employees\""));
        assertTrue(new MySqlEmitter().visitSelectSql(select)
                .contains("`employees`.`id` NOT IN (SELECT `employees`.`manager_id` FROM `test`.`employees`"));
    }

    @Test
    void lowersInValuesToInList() throws Exception {
        SelectSql select = lowerSingleSelect("LoweringIn", """
                        select(EMPLOYEES.NAME)
                                .from(EMPLOYEES)
                                .where(EMPLOYEES.ID.in(1, 2))
                                .fetch();
                """);

        InListExpression in = assertInstanceOf(InListExpression.class, select.where());
        assertFalse(in.negated());
        assertEquals(2, in.items().size());
    }

    @Test
    void lowersNullsOrderingOnSortFields() throws Exception {
        SelectSql select = lowerSingleSelect("LoweringNullsOrder", """
                        select(EMPLOYEES.NAME)
                                .from(EMPLOYEES)
                                .orderBy(EMPLOYEES.SCORE.desc().nullsLast(), EMPLOYEES.NAME.asc())
                                .fetch();
                """);

        assertEquals(2, select.orderBy().size());
        OrderBySpec scored = select.orderBy().get(0);
        assertEquals(SortDirection.DESC, scored.direction());
        assertEquals(NullsOrder.LAST, scored.nulls());
        assertNull(select.orderBy().get(1).nulls());

        assertTrue(new PostgreSqlEmitter().visitSelectSql(select)
                .contains("ORDER BY \"employees\".\"score\" DESC NULLS LAST, \"employees\".\"name\" ASC"));
        // MySQL emulates with a leading (expr IS NULL) sort key.
        assertTrue(new MySqlEmitter().visitSelectSql(select)
                .contains("ORDER BY (`employees`.`score` IS NULL) ASC, `employees`.`score` DESC, `employees`.`name` ASC"));
    }

    @Test
    void lowersSearchedCaseProjectionToCaseWhenExpression() throws Exception {
        SelectSql select = lowerSingleSelect("LoweringSearchedCase", """
                        select(when(EMPLOYEES.SCORE.ge(90), "A").when(EMPLOYEES.SCORE.ge(50), "B").otherwise("C"))
                                .from(EMPLOYEES)
                                .fetch();
                """);

        CaseWhenExpression caseWhen = assertInstanceOf(CaseWhenExpression.class, select.columns().getFirst().expression());
        assertEquals(2, caseWhen.conditions().size());
        BinaryOpExpression first = assertInstanceOf(BinaryOpExpression.class, caseWhen.conditions().get(0).condition());
        assertEquals(BinaryOperator.GREATER_THAN_OR_EQUAL, first.operator());
        assertEquals(90, assertInstanceOf(LiteralExpression.class, first.right()).value());
        assertEquals("A", assertInstanceOf(LiteralExpression.class, caseWhen.conditions().get(0).value()).value());
        assertEquals("B", assertInstanceOf(LiteralExpression.class, caseWhen.conditions().get(1).value()).value());
        assertEquals("C", assertInstanceOf(LiteralExpression.class, caseWhen.elseValue()).value());

        assertTrue(new PostgreSqlEmitter().visitSelectSql(select)
                .contains("CASE WHEN (\"employees\".\"score\" >= 90) THEN 'A' "
                        + "WHEN (\"employees\".\"score\" >= 50) THEN 'B' ELSE 'C' END"));
        assertTrue(new MySqlEmitter().visitSelectSql(select)
                .contains("CASE WHEN (`employees`.`score` >= 90) THEN 'A' "
                        + "WHEN (`employees`.`score` >= 50) THEN 'B' ELSE 'C' END"));
    }

    @Test
    void aliasedColumnAgainstUnjoinedAliasIsRejected() throws Exception {
        Path sourceFile = tempDir.resolve("LoweringUnjoinedAlias.java");
        Files.writeString(sourceFile, """
                import titan.dsl.*;
                import static titan.dsl.DSL.*;

                class LoweringUnjoinedAlias {
                    static final EmployeesTable EMPLOYEES = new EmployeesTable();

                    @StoredProcedure
                    public static void run() {
                        AliasedTable<Object> e = EMPLOYEES.as("e");
                        AliasedTable<Object> ghost = EMPLOYEES.as("ghost");
                        select(ghost.col(EMPLOYEES.NAME))
                                .from(e)
                                .fetch();
                    }

                %s
                }
                """.formatted(EMPLOYEES_TABLE));

        ParsedSources parsed = new JavaSourceParser().parse(List.of(sourceFile), List.of(), "21", false);
        List<DiscoveredEntryPoint> entryPoints = new EntryPointDiscovery().discover(parsed);

        TitanDiagnosticException exception = assertThrows(TitanDiagnosticException.class,
                () -> new JavaToTirLowerer().lower(parsed, entryPoints));
        assertTrue(exception.getMessage().contains("ghost"), exception.getMessage());
    }

    @Test
    void nonLiteralAliasIsRejectedWithPositionedDiagnostic() throws Exception {
        Path sourceFile = tempDir.resolve("LoweringDynamicAlias.java");
        Files.writeString(sourceFile, """
                import titan.dsl.*;
                import static titan.dsl.DSL.*;

                class LoweringDynamicAlias {
                    static final EmployeesTable EMPLOYEES = new EmployeesTable();

                    @StoredProcedure
                    public static void run(String dynamicAlias) {
                        select(EMPLOYEES.NAME)
                                .from(EMPLOYEES.as(dynamicAlias))
                                .fetch();
                    }

                %s
                }
                """.formatted(EMPLOYEES_TABLE));

        ParsedSources parsed = new JavaSourceParser().parse(List.of(sourceFile), List.of(), "21", false);
        List<DiscoveredEntryPoint> entryPoints = new EntryPointDiscovery().discover(parsed);

        TitanDiagnosticException exception = assertThrows(TitanDiagnosticException.class,
                () -> new JavaToTirLowerer().lower(parsed, entryPoints));
        assertTrue(exception.getMessage().contains("alias"), exception.getMessage());
    }
}
