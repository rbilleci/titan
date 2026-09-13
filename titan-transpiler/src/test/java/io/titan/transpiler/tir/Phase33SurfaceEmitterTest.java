package io.titan.transpiler.tir;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Phase 3.3 (audit D-7) emitter coverage on constructed TIR for both dialects: DISTINCT,
 * structured FROM/JOIN aliases (self-joins), NULLS FIRST/LAST ordering (native vs emulated),
 * IN/NOT IN list and subquery forms — all identifiers quoted per dialect.
 */
class Phase33SurfaceEmitterTest {

    private static SelectSql selfJoinSelect() {
        return new SelectSql(
                List.of(
                        new SelectColumn(new ColumnRefExpression("e", "name"), null),
                        new SelectColumn(new ColumnRefExpression("m", "name"), "manager_name")),
                true,
                "employees",
                "e",
                List.of(new JoinSpec(
                        JoinType.LEFT,
                        "employees",
                        "m",
                        new BinaryOpExpression(
                                new ColumnRefExpression("e", "manager_id"),
                                BinaryOperator.EQUAL,
                                new ColumnRefExpression("m", "id")))),
                null,
                List.of(),
                null,
                List.of(
                        new OrderBySpec(new ColumnRefExpression("e", "score"), SortDirection.DESC, NullsOrder.LAST),
                        new OrderBySpec(new ColumnRefExpression("e", "name"), SortDirection.ASC)),
                null,
                null,
                false,
                null,
                List.of());
    }

    @Test
    void postgresEmitsDistinctAliasedSelfJoinWithNativeNullsOrdering() {
        assertEquals(
                "SELECT DISTINCT \"e\".\"name\", \"m\".\"name\" AS \"manager_name\" "
                        + "FROM \"employees\" AS \"e\" "
                        + "LEFT JOIN \"employees\" AS \"m\" ON (\"e\".\"manager_id\" = \"m\".\"id\") "
                        + "ORDER BY \"e\".\"score\" DESC NULLS LAST, \"e\".\"name\" ASC",
                new PostgreSqlEmitter().visitSelectSql(selfJoinSelect()));
    }

    @Test
    void mysqlEmitsDistinctAliasedSelfJoinWithEmulatedNullsOrdering() {
        assertEquals(
                "SELECT DISTINCT `e`.`name`, `m`.`name` AS `manager_name` "
                        + "FROM `employees` AS `e` "
                        + "LEFT JOIN `employees` AS `m` ON (`e`.`manager_id` = `m`.`id`) "
                        + "ORDER BY (`e`.`score` IS NULL) ASC, `e`.`score` DESC, `e`.`name` ASC",
                new MySqlEmitter().visitSelectSql(selfJoinSelect()));
    }

    @Test
    void nullsFirstEmitsNativeAndEmulatedForms() {
        SelectSql select = new SelectSql(
                List.of(new SelectColumn(new ColumnRefExpression(null, "name"), null)),
                "employees",
                List.of(),
                null,
                List.of(),
                null,
                List.of(new OrderBySpec(new ColumnRefExpression(null, "score"), SortDirection.ASC, NullsOrder.FIRST)),
                null,
                null,
                false,
                null,
                List.of());

        assertEquals("SELECT \"name\" FROM \"employees\" ORDER BY \"score\" ASC NULLS FIRST",
                new PostgreSqlEmitter().visitSelectSql(select));
        assertEquals("SELECT `name` FROM `employees` ORDER BY (`score` IS NULL) DESC, `score` ASC",
                new MySqlEmitter().visitSelectSql(select));
    }

    @Test
    void inListAndNotInSubqueryEmitOnBothDialects() {
        SelectSql subquery = new SelectSql(
                List.of(new SelectColumn(new ColumnRefExpression(null, "manager_id"), null)),
                "employees",
                List.of(),
                null,
                List.of(),
                null,
                List.of(),
                null,
                null,
                false,
                null,
                List.of());
        InListExpression inValues = new InListExpression(
                new ColumnRefExpression(null, "id"),
                List.of(new LiteralExpression(3, new TIntType()), new LiteralExpression(5, new TIntType())),
                false);
        InListExpression notInSubquery = new InListExpression(
                new ColumnRefExpression(null, "id"),
                subquery,
                true);

        PostgreSqlEmitter postgres = new PostgreSqlEmitter();
        MySqlEmitter mysql = new MySqlEmitter();

        assertEquals("\"id\" IN (3, 5)", postgres.visitInListExpression(inValues));
        assertEquals("`id` IN (3, 5)", mysql.visitInListExpression(inValues));
        assertEquals("\"id\" NOT IN (SELECT \"manager_id\" FROM \"employees\")",
                postgres.visitInListExpression(notInSubquery));
        assertEquals("`id` NOT IN (SELECT `manager_id` FROM `employees`)",
                mysql.visitInListExpression(notInSubquery));
    }

    @Test
    void existsWrapperPreservesDistinctAndAliases() {
        SelectSql select = new SelectSql(
                List.of(new SelectColumn(new ColumnRefExpression("e", "id"), null)),
                true,
                "employees",
                "e",
                List.of(),
                null,
                List.of(),
                null,
                List.of(),
                null,
                null,
                true,
                null,
                List.of());

        assertEquals("SELECT EXISTS (SELECT DISTINCT \"e\".\"id\" FROM \"employees\" AS \"e\")",
                new PostgreSqlEmitter().visitSelectSql(select));
        assertEquals("SELECT EXISTS (SELECT DISTINCT `e`.`id` FROM `employees` AS `e`)",
                new MySqlEmitter().visitSelectSql(select));
    }
}
