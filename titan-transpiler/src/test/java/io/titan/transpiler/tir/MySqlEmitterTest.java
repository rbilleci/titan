package io.titan.transpiler.tir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.titan.transpiler.NamingConventionEngine;
import java.util.List;
import org.junit.jupiter.api.Test;

class MySqlEmitterTest {

    @Test
    void emitsProcedureWithCoreP0Statements() {
        Block body = new Block(
                List.of(new DeclareVariable("v_total", new TIntType(), false, new LiteralExpression(0, new TIntType()))),
                List.of(
                        new Assign(new VariableRefExpression("v_total"), new LiteralExpression(1, new TIntType())),
                        new IfStatement(
                                new BinaryOpExpression(
                                        new VariableRefExpression("v_total"),
                                        BinaryOperator.GREATER_THAN,
                                        new LiteralExpression(0, new TIntType())),
                                new Block(List.of(), List.of(new CallStatement("do_work", List.of())), List.of()),
                                List.of(),
                                null),
                        new WhileStatement(
                                new BinaryOpExpression(
                                        new VariableRefExpression("v_total"),
                                        BinaryOperator.LESS_THAN,
                                        new LiteralExpression(10, new TIntType())),
                                new Block(
                                        List.of(),
                                        List.of(new Assign(
                                                new VariableRefExpression("v_total"),
                                                new BinaryOpExpression(
                                                        new VariableRefExpression("v_total"),
                                                        BinaryOperator.ADD,
                                                        new LiteralExpression(1, new TIntType())))),
                                        List.of()),
                                null),
                        new NullGuardStatement("v_total", "Sample.java:42"),
                        new DebugPrintStatement(new LiteralExpression("tick", new TTextType())),
                        new ReturnStatement(null)
                ),
                List.of());

        String sql = new MySqlEmitter().emitProcedure("app", "process_accounts", SecurityMode.INVOKER, body);

        assertTrue(sql.contains("DELIMITER $$"));
        assertTrue(sql.contains("DROP PROCEDURE IF EXISTS `app`.`process_accounts`$$"));
        assertTrue(sql.contains("CREATE PROCEDURE `app`.`process_accounts`()"));
        assertTrue(sql.contains("SQL SECURITY INVOKER"));
        assertTrue(sql.contains("DECLARE v_total INT DEFAULT 0;"));
        assertTrue(sql.contains("CREATE TEMPORARY TABLE IF NOT EXISTS __debug_log(message TEXT);"));
        assertTrue(sql.contains("SET v_total = 1;"));
        assertTrue(sql.contains("IF (v_total > 0) THEN"));
        assertTrue(sql.contains("CALL `do_work`();"));
        assertTrue(sql.contains("WHILE (v_total < 10) DO"));
        assertTrue(sql.contains("-- titan:source:Sample.java:42"));
        assertTrue(sql.contains("IF v_total IS NULL THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'NullPointerException at Sample.java:42'; END IF;"));
        assertTrue(sql.contains("INSERT INTO __debug_log(message) VALUES ('tick');"));
        assertTrue(sql.contains("DROP TEMPORARY TABLE IF EXISTS __debug_log;"));
        // MySQL procedures cannot RETURN (function-only); a void return LEAVEs the labeled body
        // block and falls through to the epilogue. A bare RETURN; here would fail to deploy.
        assertTrue(sql.contains("proc_body: BEGIN"), sql);
        assertTrue(sql.contains("LEAVE proc_body;"), sql);
        assertFalse(sql.contains("RETURN;"), "MySQL procedure must not emit RETURN; " + sql);
        assertTrue(sql.contains("DELIMITER ;"));
    }

    @Test
    void removesAbsolutePathsFromPublishedNullGuardProvenance() {
        String sql = new MySqlEmitter().visitNullGuardStatement(
                new NullGuardStatement("v_total", "/private/build-agent/workspace/secrets/Source.java:42"));

        assertTrue(sql.contains("-- titan:source:Source.java:42"), sql);
        assertTrue(sql.contains("NullPointerException at Source.java:42"), sql);
        assertFalse(sql.contains("/private/build-agent"), sql);
    }

    @Test
    void rejectsUnrewrittenCompareToMarker() {
        // N2 invariant (plan 2.3): the __titan_compare_to marker emitted by the lowerer must be
        // rewritten (and null-guarded) by NullAnalysisPass; reaching the emitter means the pass
        // was skipped and the comparison would silently mis-handle NULL operands.
        FunctionCallExpression marker = new FunctionCallExpression(
                NullAnalysisPass.COMPARE_TO_MARKER,
                List.of(new VariableRefExpression("a"), new VariableRefExpression("b")),
                null);

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> new MySqlEmitter().visitFunctionCallExpression(marker));
        assertTrue(exception.getMessage().contains("NullAnalysisPass"));
    }

    @Test
    void emitsEmulationDivisionAndModuloMarkersAsRuntimeCalls() {
        // Plan 2.4 (E-7): native MySQL INT/INT division yields DECIMAL and /0 yields NULL even
        // under the default strict sql_mode, so the EmulationInsertionPass markers call the
        // titan_rt helpers (DIV/MOD with an explicit SIGNAL SQLSTATE '22012' zero guard).
        MySqlEmitter emitter = new MySqlEmitter();
        List<ExpressionNode> args = List.of(new VariableRefExpression("a"), new VariableRefExpression("b"));

        assertEquals("titan_rt_java_int_div(a, b)", emitter.visitFunctionCallExpression(
                new FunctionCallExpression(EmulationInsertionPass.INT_DIV_MARKER, args, null)));
        assertEquals("titan_rt_java_mod(a, b)", emitter.visitFunctionCallExpression(
                new FunctionCallExpression(EmulationInsertionPass.INT_MOD_MARKER, args, null)));
    }

    @Test
    void emitsWraparoundMarkersAsRuntimeCalls() {
        // Plan 2.4 (E-11): strict-wraparound markers call the (previously missing) titan_rt
        // 32-bit wraparound helpers.
        MySqlEmitter emitter = new MySqlEmitter();
        List<ExpressionNode> args = List.of(new VariableRefExpression("a"), new VariableRefExpression("b"));

        assertEquals("titan_rt_java_int_add(a, b)", emitter.visitFunctionCallExpression(
                new FunctionCallExpression(EmulationInsertionPass.INT_ADD_MARKER, args, null)));
        assertEquals("titan_rt_java_int_sub(a, b)", emitter.visitFunctionCallExpression(
                new FunctionCallExpression(EmulationInsertionPass.INT_SUB_MARKER, args, null)));
        assertEquals("titan_rt_java_int_mul(a, b)", emitter.visitFunctionCallExpression(
                new FunctionCallExpression(EmulationInsertionPass.INT_MUL_MARKER, args, null)));
    }

    @Test
    void emitsObservabilityPayloadWithParameterTypes() {
        Block body = new Block(
                List.of(new DeclareVariable("p_account_id", new TIntType(), false, null)),
                List.of(new ReturnStatement(null)),
                List.of());

        String sql = new MySqlEmitter().emitProcedure(
                "app",
                "telemetry_proc",
                SecurityMode.INVOKER,
                body,
                true,
                List.of("accounts.email"));

        assertTrue(sql.contains("JSON_OBJECT('parameterTypes', JSON_OBJECT('p_account_id', 'INT'), 'sensitiveColumns', JSON_ARRAY('accounts.email'))"));
    }

    @Test
    void emitsObservabilitySuccessBeforeFunctionReturn() {
        Block body = new Block(
                List.of(),
                List.of(new ReturnStatement(new LiteralExpression(7, new TIntType()))),
                List.of());

        String sql = new MySqlEmitter().emitFunction(
                "app",
                "telemetry_fn",
                SecurityMode.INVOKER,
                new TIntType(),
                body,
                true);

        int success = sql.indexOf("status = 'success'");
        int restoreLastInsertId = sql.indexOf("DO LAST_INSERT_ID(__titan_saved_last_insert_id);", success);
        int stagedValue = sql.indexOf("SET __titan_return_value = 7;");
        int returnStatement = sql.indexOf("RETURN __titan_return_value;");
        assertTrue(success >= 0);
        assertTrue(restoreLastInsertId > success, sql);
        // E-8: the return value is staged under UTC after the telemetry success UPDATE, the
        // caller's time zone is restored, then the staged value is returned.
        assertTrue(stagedValue > success);
        assertTrue(sql.indexOf("SET time_zone = __titan_saved_time_zone;", stagedValue) > stagedValue);
        assertTrue(returnStatement > stagedValue);
    }

    @Test
    void emitsRoutineParametersFromPrefixedDeclarations() {
        Block body = new Block(
                List.of(
                        new DeclareVariable("p_account_id", new TIntType(), false, null),
                        new DeclareVariable("v_total", new TIntType(), false, new LiteralExpression(0, new TIntType()))),
                List.of(new ReturnStatement(null)),
                List.of());

        String procedureSql = new MySqlEmitter().emitProcedure("app", "proc_with_params", SecurityMode.INVOKER, body);
        assertTrue(procedureSql.contains("CREATE PROCEDURE `app`.`proc_with_params`(IN p_account_id INT)"));
        assertFalse(procedureSql.contains("DECLARE p_account_id"));
        assertTrue(procedureSql.contains("DECLARE v_total INT DEFAULT 0;"));

        String functionSql = new MySqlEmitter().emitFunction("app", "fn_with_params", SecurityMode.INVOKER, new TIntType(), body);
        assertTrue(functionSql.contains("CREATE FUNCTION `app`.`fn_with_params`(p_account_id INT)"));
        assertFalse(functionSql.contains("DECLARE p_account_id"));
    }

    @Test
    void disambiguatesCollidingRoutineParameterSqlNames() {
        Block body = new Block(
                List.of(),
                List.of(new ReturnStatement(new BinaryOpExpression(
                        new VariableRefExpression("userId"),
                        BinaryOperator.ADD,
                        new VariableRefExpression("user_id")))),
                List.of());

        String sql = new MySqlEmitter().emitFunction(
                "app",
                "sum_users",
                SecurityMode.INVOKER,
                new TIntType(),
                body,
                List.of(
                        new RoutineParameter("userId", "p_user_id", new TIntType()),
                        new RoutineParameter("user_id", "p_user_id", new TIntType())));

        assertTrue(sql.contains("CREATE FUNCTION `app`.`sum_users`(p_user_id INT, p_user_id_2 INT)"));
        assertTrue(sql.contains("SET __titan_return_value = (p_user_id + p_user_id_2);"));
        assertTrue(sql.contains("RETURN __titan_return_value;"));
    }

    @Test
    void preservesInternalTelemetryIdentifiersByRenamingConflictingLocals() {
        Block body = new Block(
                List.of(new DeclareVariable("__titan_started_at", new TIntType(), false, new LiteralExpression(1, new TIntType()))),
                List.of(new ReturnStatement(null)),
                List.of());

        String sql = new MySqlEmitter().emitProcedure("app", "telemetry_guard", SecurityMode.INVOKER, body, true);

        assertTrue(sql.contains("DECLARE __titan_started_at_2 INT DEFAULT 1;"));
        assertTrue(sql.contains("DECLARE __titan_started_at TIMESTAMP(6) DEFAULT CURRENT_TIMESTAMP(6);"));
    }

    @Test
    void cleansUpDebugLogTableInObservabilityExceptionHandler() {
        Block body = new Block(
                List.of(),
                List.of(new DebugPrintStatement(new LiteralExpression("x", new TTextType()))),
                List.of());

        String sql = new MySqlEmitter().emitProcedure("app", "obs_debug", SecurityMode.INVOKER, body, true, List.of());

        assertTrue(sql.contains("DECLARE EXIT HANDLER FOR SQLEXCEPTION"));
        assertTrue(sql.contains("DROP TEMPORARY TABLE IF EXISTS __debug_log;"));
    }

    @Test
    void emitsRepeatLoopForLoopStatementsWithExitCondition() {
        Block body = new Block(
                List.of(),
                List.of(new LoopStatement(
                        new Block(
                                List.of(),
                                List.of(new Assign(
                                        new VariableRefExpression("v_total"),
                                        new BinaryOpExpression(
                                                new VariableRefExpression("v_total"),
                                                BinaryOperator.ADD,
                                                new LiteralExpression(1, new TIntType())))),
                                List.of()),
                        new BinaryOpExpression(
                                new VariableRefExpression("v_total"),
                                BinaryOperator.GREATER_THAN,
                                new LiteralExpression(3, new TIntType())),
                        "retry_loop")),
                List.of());

        String sql = new MySqlEmitter().emitProcedure("app", "loop_proc", SecurityMode.INVOKER, body);

        assertTrue(sql.contains("retry_loop: REPEAT"));
        assertTrue(sql.contains("UNTIL (v_total > 3) END REPEAT retry_loop;"));
        assertFalse(sql.contains("LEAVE retry_loop"));
    }

    @Test
    void emitsFunctionWithSqlStatementExecution() {
        SelectSql selectSql = new SelectSql(
                List.of(new SelectColumn(new VariableRefExpression("v_total"), "total")),
                "accounts",
                List.of(),
                null,
                List.of(),
                null,
                List.of(),
                1,
                null,
                null,
                List.of());

        Block body = new Block(
                List.of(),
                List.of(
                        new ExecuteSqlStatement(selectSql),
                        new RaiseStatement("45000", new LiteralExpression("boom", new TTextType()), List.of()),
                        new ReturnStatement(new VariableRefExpression("v_total"))
                ),
                List.of());

        String sql = new MySqlEmitter().emitFunction("app", "count_active", SecurityMode.INVOKER, new TIntType(), body);

        assertTrue(sql.contains("DROP FUNCTION IF EXISTS `app`.`count_active`$$"));
        assertTrue(sql.contains("CREATE FUNCTION `app`.`count_active`()"));
        assertTrue(sql.contains("RETURNS INT"));
        assertFalse(sql.contains("CREATE TEMPORARY TABLE IF NOT EXISTS __debug_log(message TEXT);"));
        assertTrue(sql.contains("SELECT v_total AS `total` FROM `accounts` LIMIT 1;"));
        assertTrue(sql.contains("SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'boom';"));
        assertTrue(sql.contains("SET __titan_return_value = v_total;"));
        assertTrue(sql.contains("RETURN __titan_return_value;"));
    }

    @Test
    void emitsFetchExistsSelectWrapperWithoutOrderLimitOrLocking() {
        SelectSql selectSql = new SelectSql(
                List.of(new SelectColumn(new ColumnRefExpression("accounts", "id"), null)),
                "accounts",
                List.of(),
                new BinaryOpExpression(new ColumnRefExpression("accounts", "active"), BinaryOperator.EQUAL, new LiteralExpression(true, new TBooleanType())),
                List.of(),
                null,
                List.of(new OrderBySpec(new ColumnRefExpression("accounts", "id"), SortDirection.ASC)),
                5,
                2,
                true,
                new LockingClause(true, false, true, false),
                List.of());

        assertEquals(
                "SELECT EXISTS (SELECT `accounts`.`id` FROM `accounts` WHERE (`accounts`.`active` = TRUE))",
                new MySqlEmitter().visitSelectSql(selectSql));
    }

    @Test
    void emitsSelectWithNonRecursiveCteForMySql() {
        SelectSql cteQuery = new SelectSql(
                List.of(new SelectColumn(new ColumnRefExpression("accounts", "id"), null)),
                "accounts",
                List.of(),
                null,
                List.of(),
                null,
                List.of(),
                null,
                null,
                null,
                List.of());

        SelectSql selectSql = new SelectSql(
                List.of(new SelectColumn(new ColumnRefExpression("recent_accounts", "id"), null)),
                "recent_accounts",
                List.of(),
                null,
                List.of(),
                null,
                List.of(),
                null,
                null,
                null,
                List.of(new CteSpec("recent_accounts", cteQuery, false)));

        String sql = new MySqlEmitter().visitSelectSql(selectSql);

        assertTrue(sql.startsWith("WITH `recent_accounts` AS (SELECT `accounts`.`id` FROM `accounts`) SELECT `recent_accounts`.`id` FROM `recent_accounts`"));
    }

    @Test
    void emitsSelectWithRecursiveCteForMySql() {
        SelectSql recursiveCteQuery = new SelectSql(
                List.of(new SelectColumn(new LiteralExpression(1, new TIntType()), "value")),
                null,
                List.of(),
                null,
                List.of(),
                null,
                List.of(),
                null,
                null,
                null,
                List.of());

        SelectSql selectSql = new SelectSql(
                List.of(new SelectColumn(new ColumnRefExpression("nums", "value"), null)),
                "nums",
                List.of(),
                null,
                List.of(),
                null,
                List.of(),
                null,
                null,
                null,
                List.of(new CteSpec("nums", recursiveCteQuery, true)));

        String sql = new MySqlEmitter().visitSelectSql(selectSql);

        assertTrue(sql.startsWith("WITH RECURSIVE `nums` AS (SELECT 1 AS `value`) SELECT `nums`.`value` FROM `nums`"));
    }

    @Test
    void emitsSelectGroupByAndHavingForMySql() {
        SelectSql selectSql = new SelectSql(
                List.of(
                        new SelectColumn(new ColumnRefExpression("accounts", "plan_id"), "plan_id"),
                        new SelectColumn(new FunctionCallExpression("COUNT", List.of(new LiteralExpression(1, new TIntType())), null), "cnt")),
                "accounts",
                List.of(),
                null,
                List.of(new ColumnRefExpression("accounts", "plan_id")),
                new BinaryOpExpression(
                        new FunctionCallExpression("COUNT", List.of(new LiteralExpression(1, new TIntType())), null),
                        BinaryOperator.GREATER_THAN,
                        new LiteralExpression(5, new TIntType())),
                List.of(new OrderBySpec(new ColumnRefExpression("accounts", "plan_id"), SortDirection.ASC)),
                null,
                null,
                null,
                List.of());

        String sql = new MySqlEmitter().visitSelectSql(selectSql);

        assertTrue(sql.contains("GROUP BY `accounts`.`plan_id`"));
        assertTrue(sql.contains("HAVING (COUNT(1) > 5)"));
        assertTrue(sql.contains("ORDER BY `accounts`.`plan_id` ASC"));
    }

    @Test
    void emitsSelectLockingClausesForMySql() {
        SelectSql selectSql = new SelectSql(
                List.of(new SelectColumn(new ColumnRefExpression("accounts", "id"), null)),
                "accounts",
                List.of(),
                null,
                List.of(),
                null,
                List.of(),
                1,
                null,
                new LockingClause(false, true, true, true),
                List.of());

        String sql = new MySqlEmitter().visitSelectSql(selectSql);

        assertTrue(sql.contains("SELECT `accounts`.`id` FROM `accounts` LIMIT 1 FOR SHARE SKIP LOCKED NOWAIT"));
    }

    @Test
    void emitsLateralJoinForMySqlSelectSql() {
        SelectSql lateralQuery = new SelectSql(
                List.of(new SelectColumn(new ColumnRefExpression("plans", "name"), null)),
                "plans",
                List.of(),
                null,
                List.of(),
                null,
                List.of(),
                1,
                null,
                null,
                List.of());
        SelectSql selectSql = new SelectSql(
                List.of(new SelectColumn(new ColumnRefExpression("accounts", "email"), null)),
                "accounts",
                List.of(new JoinSpec(JoinType.LATERAL, new LateralSubquery(lateralQuery, "plan_lateral"), null)),
                null,
                List.of(),
                null,
                List.of(),
                null,
                null,
                null,
                List.of());

        String sql = new MySqlEmitter().visitSelectSql(selectSql);

        assertTrue(sql.contains("FROM `accounts` JOIN LATERAL (SELECT `plans`.`name` FROM `plans` LIMIT 1) AS `plan_lateral`"));
    }

    @Test
    void emitsExistsExpressionsForMySql() {
        SelectSql subquery = new SelectSql(
                List.of(new SelectColumn(new ColumnRefExpression("accounts", "id"), null)),
                "accounts",
                List.of(),
                new BinaryOpExpression(
                        new ColumnRefExpression("accounts", "plan_code"),
                        BinaryOperator.EQUAL,
                        new LiteralExpression("enterprise", new TTextType())),
                List.of(),
                null,
                List.of(),
                1,
                null,
                null,
                List.of());

        MySqlEmitter emitter = new MySqlEmitter();

        assertEquals(
                "EXISTS (SELECT `accounts`.`id` FROM `accounts` WHERE (`accounts`.`plan_code` = 'enterprise') LIMIT 1)",
                emitter.visitExistsExpression(new ExistsExpression(subquery, false)));
        assertEquals(
                "NOT (EXISTS (SELECT `accounts`.`id` FROM `accounts` WHERE (`accounts`.`plan_code` = 'enterprise') LIMIT 1))",
                emitter.visitExistsExpression(new ExistsExpression(subquery, true)));
    }

    @Test
    void escapesWindowFunctionStringArgumentsForMySql() {
        WindowFunctionExpression lag = new WindowFunctionExpression(
                "LAG",
                List.of(
                        new ColumnRefExpression("accounts", "email"),
                        new LiteralExpression(1, new TIntType()),
                        new LiteralExpression("o'brien\\fallback", new TTextType())),
                new WindowSpec(
                        List.of(new ColumnRefExpression("accounts", "plan_id")),
                        List.of(new OrderBySpec(new ColumnRefExpression("accounts", "id"), SortDirection.ASC)),
                        new WindowFrame(
                                WindowFrameUnit.ROWS,
                                new WindowFrameBound(WindowFrameBoundKind.UNBOUNDED_PRECEDING, null),
                                new WindowFrameBound(WindowFrameBoundKind.CURRENT_ROW, null))));

        // MySQL string escaping doubles both quotes and backslashes.
        assertEquals(
                "LAG(`accounts`.`email`, 1, 'o''brien\\\\fallback') OVER (PARTITION BY `accounts`.`plan_id`"
                        + " ORDER BY `accounts`.`id` ASC ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW)",
                new MySqlEmitter().visitWindowFunctionExpression(lag));
    }

    @Test
    void emitsSoleRollupGroupByAsWithRollupForMySql() {
        SelectSql selectSql = new SelectSql(
                List.of(
                        new SelectColumn(new ColumnRefExpression("accounts", "tenant_id"), null),
                        new SelectColumn(new FunctionCallExpression("COUNT", List.of(new LiteralExpression(1, new TIntType())), null), "cnt")),
                "accounts",
                List.of(),
                null,
                List.of(new GroupingSetSpec(
                        GroupingSetKind.ROLLUP,
                        List.of(List.of(
                                new ColumnRefExpression("accounts", "tenant_id"),
                                new ColumnRefExpression("accounts", "status"))))),
                null,
                List.of(),
                null,
                null,
                null,
                List.of());

        String sql = new MySqlEmitter().visitSelectSql(selectSql);

        assertTrue(sql.contains("GROUP BY `accounts`.`tenant_id`, `accounts`.`status` WITH ROLLUP"), sql);
    }

    @Test
    void rejectsGroupingSetsAndCubeAndMixedRollupForMySql() {
        MySqlEmitter emitter = new MySqlEmitter();
        ColumnRefExpression tenant = new ColumnRefExpression("accounts", "tenant_id");
        ColumnRefExpression status = new ColumnRefExpression("accounts", "status");

        UnsupportedOperationException groupingSetsError = assertThrows(
                UnsupportedOperationException.class,
                () -> emitter.visitGroupingSetSpec(new GroupingSetSpec(
                        GroupingSetKind.GROUPING_SETS,
                        List.of(List.of(tenant, status), List.of()))));
        assertTrue(groupingSetsError.getMessage().contains("TITAN-E001"));
        assertTrue(groupingSetsError.getMessage().contains("GROUPING SETS"));

        UnsupportedOperationException cubeError = assertThrows(
                UnsupportedOperationException.class,
                () -> emitter.visitGroupingSetSpec(new GroupingSetSpec(GroupingSetKind.CUBE, List.of(List.of(status)))));
        assertTrue(cubeError.getMessage().contains("TITAN-E001"));
        assertTrue(cubeError.getMessage().contains("CUBE"));

        // ROLLUP combined with other GROUP BY elements cannot be expressed as WITH ROLLUP.
        SelectSql mixedRollup = new SelectSql(
                List.of(new SelectColumn(tenant, null)),
                "accounts",
                List.of(),
                null,
                List.of(
                        new GroupingSetSpec(GroupingSetKind.ROLLUP, List.of(List.of(tenant))),
                        status),
                null,
                List.of(),
                null,
                null,
                null,
                List.of());
        UnsupportedOperationException mixedError = assertThrows(
                UnsupportedOperationException.class,
                () -> emitter.visitSelectSql(mixedRollup));
        assertTrue(mixedError.getMessage().contains("TITAN-E001"));
        assertTrue(mixedError.getMessage().contains("WITH ROLLUP"));
    }

    @Test
    void rejectsGroupsWindowFramesForMySql() {
        WindowFunctionExpression window = new WindowFunctionExpression(
                "ROW_NUMBER",
                List.of(),
                new WindowSpec(
                        List.of(),
                        List.of(new OrderBySpec(new ColumnRefExpression("accounts", "id"), SortDirection.ASC)),
                        new WindowFrame(
                                WindowFrameUnit.GROUPS,
                                new WindowFrameBound(WindowFrameBoundKind.UNBOUNDED_PRECEDING, null),
                                new WindowFrameBound(WindowFrameBoundKind.CURRENT_ROW, null))));

        UnsupportedOperationException error = assertThrows(
                UnsupportedOperationException.class,
                () -> new MySqlEmitter().visitWindowFunctionExpression(window));

        assertTrue(error.getMessage().contains("TITAN-E001"));
        assertTrue(error.getMessage().contains("GROUPS"));
    }

    @Test
    void rejectsFullOuterJoinForMySqlSelectSql() {
        SelectSql selectSql = new SelectSql(
                List.of(new SelectColumn(new ColumnRefExpression("accounts", "email"), null)),
                "accounts",
                List.of(new JoinSpec(
                        JoinType.FULL_OUTER,
                        "users",
                        new BinaryOpExpression(
                                new ColumnRefExpression("accounts", "id"),
                                BinaryOperator.EQUAL,
                                new ColumnRefExpression("users", "account_id")))),
                null,
                List.of(),
                null,
                List.of(),
                null,
                null,
                null,
                List.of());

        UnsupportedOperationException error = assertThrows(
                UnsupportedOperationException.class,
                () -> new MySqlEmitter().visitSelectSql(selectSql));

        assertTrue(error.getMessage().contains("TITAN-E001"));
        assertTrue(error.getMessage().contains("FULL OUTER JOIN"));
        assertTrue(error.getMessage().contains("MySQL"));
    }

    @Test
    void inlinesFixedShapeRawSqlBindingsAsRoutineLocals() {
        RawSql rawSql = new RawSql(
                "SELECT * FROM accounts WHERE id = :accountId AND status = :status",
                List.of("accountId", "status"),
                "MYSQL");

        String emitted = new MySqlEmitter().visitRawSql(rawSql);

        assertEquals("SELECT * FROM accounts WHERE id = accountId AND status = status", emitted);
        assertFalse(emitted.contains("PREPARE"), emitted);
        assertFalse(emitted.contains("@titan_"), emitted);
    }

    @Test
    void emitsFixedShapeRawSqlWithoutSessionStateAcrossStatements() {
        MySqlEmitter emitter = new MySqlEmitter();
        String first = emitter.visitRawSql(new RawSql(
                "UPDATE accounts SET status = ? WHERE id = ?", List.of("p_status", "p_id"), "MYSQL"));
        String second = emitter.visitRawSql(new RawSql(
                "UPDATE accounts SET status = ? WHERE id = ?", List.of("p_status", "p_id"), "MYSQL"));

        assertEquals("UPDATE accounts SET status = p_status WHERE id = p_id", first);
        assertEquals(first, second);
        assertFalse(first.contains("@titan_"), first);
    }

    @Test
    void givesDynamicSqlSessionVariablesStableDeclaredTypesAcrossProcedureInvocations() {
        Block body = new Block(
                List.of(
                        new DeclareVariable("p_account_id", new TBigintType(), true, null),
                        new DeclareVariable("p_enabled", new TBooleanType(), true, null),
                        new DeclareVariable("p_table", new TTextType(), false, null),
                        new DeclareVariable("v_name", new TTextType(), true, null)),
                List.of(
                        new ExecuteSqlStatement(new RawSql(
                                "UPDATE " + RawSql.spliceMarker(1) + " SET enabled = ? WHERE id = ?",
                                List.of("p_enabled", "p_account_id"), "MYSQL", List.of(),
                                List.of(new SpliceBind(1, "p_table", SpliceBind.Kind.IDENTIFIER)))),
                        new RawReadIntoStatement(
                                List.of("v_name"),
                                new RawSql("SELECT name FROM accounts WHERE id = ?", List.of("p_account_id"), "MYSQL"))),
                List.of());

        String emitted = new MySqlEmitter().emitProcedure("app", "stable_dynamic_binds", SecurityMode.INVOKER, body);

        assertTrue(emitted.contains("SET @titan_p1_1 = CAST(p_enabled AS UNSIGNED);"), emitted);
        assertTrue(emitted.contains("SET @titan_p1_2 = CAST(p_account_id AS SIGNED);"), emitted);
        assertTrue(emitted.contains("SELECT name INTO v_name FROM accounts WHERE id = p_account_id;"), emitted);
    }

    @Test
    void emitsFixedShapeRawReadIntoAsStaticSingleRowRead() {
        RawReadIntoStatement node = new RawReadIntoStatement(
                List.of("v_balance", "v_tier"),
                new RawSql("SELECT balance, tier FROM accounts WHERE id = ?", List.of("p_account_id"), "MYSQL"));

        String emitted = new MySqlEmitter().visitRawReadIntoStatement(node);

        assertEquals(
                "BEGIN\n"
                        + "    DECLARE CONTINUE HANDLER FOR NOT FOUND BEGIN END;\n"
                        + "    SET v_balance = NULL;\n"
                        + "    SET v_tier = NULL;\n"
                        + "    SELECT balance, tier INTO v_balance, v_tier FROM accounts WHERE id = p_account_id;\n"
                        + "END;",
                emitted);
    }

    @Test
    void emitsFixedShapeRawReadsWithoutSessionStagingVariables() {
        MySqlEmitter emitter = new MySqlEmitter();
        String numericRead = emitter.visitRawReadIntoStatement(new RawReadIntoStatement(
                List.of("v_id"), new RawSql("SELECT id FROM customers WHERE id = ?", List.of("p_id"), "MYSQL")));
        String textRead = emitter.visitRawReadIntoStatement(new RawReadIntoStatement(
                List.of("v_name"), new RawSql("SELECT name FROM countries WHERE code = ?", List.of("p_code"), "MYSQL")));

        assertTrue(numericRead.contains("SET v_id = NULL;"), numericRead);
        assertTrue(numericRead.contains("SELECT id INTO v_id FROM customers WHERE id = p_id;"), numericRead);
        assertTrue(textRead.contains("SET v_name = NULL;"), textRead);
        assertTrue(textRead.contains("SELECT name INTO v_name FROM countries WHERE code = p_code;"), textRead);
        assertFalse(numericRead.contains("@titan_"), numericRead);
        assertFalse(textRead.contains("@titan_"), textRead);
    }

    @Test
    void rejectsRawReadIntoWithSubqueryInSelectList() {
        // Defensive INTO-injection (decision 2): a subquery in the select list opens a FROM inside
        // parentheses before the top-level FROM — splicing INTO would mis-fire, so reject hard.
        RawReadIntoStatement node = new RawReadIntoStatement(
                List.of("v_x"),
                new RawSql("SELECT (SELECT max(n) FROM other) FROM t WHERE id = ?", List.of("p_id"), "MYSQL"));

        UnsupportedOperationException ex = assertThrows(UnsupportedOperationException.class,
                () -> new MySqlEmitter().visitRawReadIntoStatement(node));
        assertTrue(ex.getMessage().startsWith("TITAN-E001"));
    }

    @Test
    void rejectsRawReadIntoWithExtractFromInSelectList() {
        // EXTRACT(<field> FROM <expr>) puts a FROM keyword inside parentheses before the real FROM.
        RawReadIntoStatement node = new RawReadIntoStatement(
                List.of("v_y"),
                new RawSql("SELECT EXTRACT(YEAR FROM created_at) FROM events WHERE id = ?", List.of("p_id"), "MYSQL"));

        UnsupportedOperationException ex = assertThrows(UnsupportedOperationException.class,
                () -> new MySqlEmitter().visitRawReadIntoStatement(node));
        assertTrue(ex.getMessage().startsWith("TITAN-E001"));
    }

    @Test
    void rejectsRawReadIntoWithoutTopLevelFrom() {
        RawReadIntoStatement node = new RawReadIntoStatement(
                List.of("v_z"),
                new RawSql("SELECT 1", List.of(), "MYSQL"));

        UnsupportedOperationException ex = assertThrows(UnsupportedOperationException.class,
                () -> new MySqlEmitter().visitRawReadIntoStatement(node));
        assertTrue(ex.getMessage().startsWith("TITAN-E001"));
    }

    @Test
    void doesNotMisfireOnFromInsideStringLiteral() {
        // A ' FROM ' inside a string literal must not be mistaken for the top-level FROM; the real
        // FROM (after the literal) is the injection point.
        RawReadIntoStatement node = new RawReadIntoStatement(
                List.of("v_label"),
                new RawSql("SELECT 'shipped FROM warehouse' FROM orders WHERE id = ?", List.of("p_id"), "MYSQL"));

        String emitted = new MySqlEmitter().visitRawReadIntoStatement(node);

        assertTrue(emitted.contains("SELECT 'shipped FROM warehouse' INTO v_label FROM orders WHERE id = p_id;"), emitted);
    }

    @Test
    void emitsRawCursorAsStaticCursorWithInlinedBindAndSafetyScaffold() {
        // JDBC I-5 over constant text (WS-C Phase 2 §4, decision 1): the SQL is inlined into a
        // static DECLARE ... CURSOR FOR <text> reusing the cursor safety scaffold; the ? bind is
        // spliced as the routine-local IDENTIFIER (never a value).
        RawCursorStatement node = new RawCursorStatement(
                List.of("v_amount"),
                new RawSql(
                        "SELECT amount FROM invoices WHERE customer_id = ? AND status = 'OVERDUE'",
                        List.of("p_customer_id"),
                        "MYSQL"),
                new Block(
                        List.of(),
                        List.of(new Assign(
                                new VariableRefExpression("v_total"),
                                new BinaryOpExpression(
                                        new VariableRefExpression("v_total"),
                                        BinaryOperator.ADD,
                                        new VariableRefExpression("v_amount")))),
                        List.of()),
                null);

        String emitted = new MySqlEmitter().visitRawCursorStatement(node);

        assertEquals(
                "BEGIN\n"
                        + "    DECLARE done_v_amount BOOLEAN DEFAULT FALSE;\n"
                        + "    DECLARE cursor_open_v_amount BOOLEAN DEFAULT FALSE;\n"
                        + "    DECLARE cur_v_amount CURSOR FOR SELECT amount FROM invoices "
                        + "WHERE customer_id = p_customer_id AND status = 'OVERDUE';\n"
                        + "    DECLARE EXIT HANDLER FOR SQLEXCEPTION\n"
                        + "    BEGIN\n"
                        + "        IF cursor_open_v_amount THEN CLOSE cur_v_amount; END IF;\n"
                        + "        RESIGNAL;\n"
                        + "    END;\n"
                        + "    DECLARE CONTINUE HANDLER FOR NOT FOUND SET done_v_amount = TRUE;\n"
                        + "    OPEN cur_v_amount;\n"
                        + "    SET cursor_open_v_amount = TRUE;\n"
                        + "    read_v_amount: LOOP\n"
                        + "        FETCH cur_v_amount INTO v_amount;\n"
                        + "        IF done_v_amount THEN LEAVE read_v_amount; END IF;\n"
                        + "    SET v_total = (v_total + v_amount);\n"
                        + "    END LOOP read_v_amount;\n"
                        + "    CLOSE cur_v_amount;\n"
                        + "    SET cursor_open_v_amount = FALSE;\n"
                        + "END;",
                emitted);
    }

    @Test
    void materializesProcedureRawCursorsForStructuralSplices() {
        RawCursorStatement cursor = new RawCursorStatement(
                List.of("v_amount"),
                new RawSql("SELECT amount FROM " + RawSql.spliceMarker(1) + " WHERE customer_id = ?",
                        List.of("p_customer_id"), "MYSQL", List.of(),
                        List.of(new SpliceBind(1, "p_invoices", SpliceBind.Kind.IDENTIFIER))),
                new Block(
                        List.of(new DeclareVariable("v_amount", new TIntType(), false, null)),
                        List.of(new Assign(
                                new VariableRefExpression("v_total"),
                                new BinaryOpExpression(
                                        new VariableRefExpression("v_total"),
                                        BinaryOperator.ADD,
                                        new VariableRefExpression("v_amount")))),
                        List.of()),
                null);
        Block body = new Block(
                List.of(
                        new DeclareVariable("p_customer_id", new TIntType(), false, null),
                        new DeclareVariable("p_invoices", new TTextType(), false, null),
                        new DeclareVariable("v_total", new TIntType(), false, new LiteralExpression(0, new TIntType()))),
                List.of(cursor),
                List.of());

        String emitted = new MySqlEmitter().emitProcedure("app", "cursor_proc", SecurityMode.INVOKER, body);

        assertTrue(emitted.contains("CREATE TEMPORARY TABLE titan_cursor_rows_v_amount "
                + "(titan_cursor_ordinal_v_amount BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY)"), emitted);
        assertTrue(emitted.contains("SET @titan_dsql_1 = CONCAT("), emitted);
        assertTrue(emitted.contains("PREPARE titan_cursor_stmt_1 FROM @titan_dsql_1;"), emitted);
        assertTrue(emitted.contains("SELECT COUNT(*) INTO cursor_row_count_v_amount "
                + "FROM titan_cursor_rows_v_amount;"), emitted);
        assertTrue(emitted.contains("SELECT * INTO cursor_row_id_v_amount, v_amount "
                + "FROM titan_cursor_rows_v_amount WHERE titan_cursor_ordinal_v_amount "
                + "= cursor_ordinal_v_amount;"), emitted);
        assertFalse(emitted.contains("CURSOR FOR"), emitted);
        assertFalse(emitted.contains("FETCH "), emitted);
        assertTrue(emitted.contains("DROP TEMPORARY TABLE IF EXISTS titan_cursor_rows_v_amount;"), emitted);
    }

    @Test
    void emitsTransactionControlAsCommitAndRollback() {
        assertEquals("COMMIT;",
                new MySqlEmitter().visitTransactionControlStatement(
                        new TransactionControlStatement(TransactionAction.COMMIT)));
        assertEquals("ROLLBACK;",
                new MySqlEmitter().visitTransactionControlStatement(
                        new TransactionControlStatement(TransactionAction.ROLLBACK)));
    }

    @Test
    void rejectsRawReadIntoWithTopLevelSetOperation() {
        // Decision 2 / emitter safety boundary: a UNION after the first SELECT...FROM block is outside
        // the simple single-block shape — injecting 'INTO' before the first FROM would land it in the
        // non-final UNION leg (a MySQL syntax error), so reject hard rather than mis-emit.
        RawReadIntoStatement union = new RawReadIntoStatement(
                List.of("v_a"),
                new RawSql("SELECT a FROM t UNION SELECT b FROM u", List.of(), "MYSQL"));
        UnsupportedOperationException unionEx = assertThrows(UnsupportedOperationException.class,
                () -> new MySqlEmitter().visitRawReadIntoStatement(union));
        assertTrue(unionEx.getMessage().startsWith("TITAN-E001"));

        RawReadIntoStatement intersect = new RawReadIntoStatement(
                List.of("v_a"),
                new RawSql("SELECT a FROM t INTERSECT SELECT a FROM u", List.of(), "MYSQL"));
        UnsupportedOperationException intersectEx = assertThrows(UnsupportedOperationException.class,
                () -> new MySqlEmitter().visitRawReadIntoStatement(intersect));
        assertTrue(intersectEx.getMessage().startsWith("TITAN-E001"));

        RawReadIntoStatement except = new RawReadIntoStatement(
                List.of("v_a"),
                new RawSql("SELECT a FROM t EXCEPT SELECT a FROM u", List.of(), "MYSQL"));
        UnsupportedOperationException exceptEx = assertThrows(UnsupportedOperationException.class,
                () -> new MySqlEmitter().visitRawReadIntoStatement(except));
        assertTrue(exceptEx.getMessage().startsWith("TITAN-E001"));
    }

    @Test
    void doesNotMisfireOnSetOperationKeywordInsideStringLiteral() {
        // A 'UNION' inside a string literal must not trip the set-operation guard; the statement is a
        // single simple SELECT...FROM block and the INTO is injected before the real FROM.
        RawReadIntoStatement node = new RawReadIntoStatement(
                List.of("v_label"),
                new RawSql("SELECT 'UNION local 42' FROM members WHERE id = ?", List.of("p_id"), "MYSQL"));

        String emitted = new MySqlEmitter().visitRawReadIntoStatement(node);

        assertTrue(emitted.contains("SELECT 'UNION local 42' INTO v_label FROM members WHERE id = p_id;"), emitted);
    }

    @Test
    void emitsFixedShapeRawReadIntoWithoutSessionBinds() {
        RawReadIntoStatement node = new RawReadIntoStatement(
                List.of("v_count"),
                new RawSql("SELECT count(*) FROM accounts", List.of(), "MYSQL"));

        String emitted = new MySqlEmitter().visitRawReadIntoStatement(node);

        assertTrue(emitted.contains("SET v_count = NULL;"), emitted);
        assertTrue(emitted.contains("SELECT count(*) INTO v_count FROM accounts;"), emitted);
    }

    @Test
    void emitsRawReadIntoGuardAsFoundHandlerNotFirstColumnNullProxy() {
        // JDBC I-4 §6.1 early-return guard on MySQL: SELECT ... INTO sets no usable ROW_COUNT()/FOUND,
        // so the no-row signal is a dedicated found flag flipped by a CONTINUE HANDLER FOR NOT FOUND
        // (the cursor scaffold), wrapping the static read in a BEGIN ... END. It is NOT a "first INTO
        // target IS NULL" proxy: an existing row with a NULL first column does not falsely raise
        // (WS-C Phase 2b audit).
        RawReadIntoStatement node = new RawReadIntoStatement(
                List.of("v_note"),
                new RawSql("SELECT optional_note FROM accounts WHERE id = ?", List.of("p_id"), "MYSQL"),
                new RaiseStatement(null, new LiteralExpression("account not found", new TTextType()), List.of()));

        String emitted = new MySqlEmitter().visitRawReadIntoStatement(node);

        assertEquals(
                "BEGIN\n"
                        + "    DECLARE titan_found BOOLEAN DEFAULT TRUE;\n"
                        + "    DECLARE CONTINUE HANDLER FOR NOT FOUND SET titan_found = FALSE;\n"
                        + "    SET v_note = NULL;\n"
                        + "    SELECT optional_note INTO v_note FROM accounts WHERE id = p_id;\n"
                        + "    IF NOT titan_found THEN\n"
                        + "        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'account not found';\n"
                        + "    END IF;\n"
                        + "END;",
                emitted);
        // The guard is FOUND-based, never a value test of the (possibly NULL) first column.
        assertFalse(emitted.contains("v_note IS NULL"), "guard must not test the first column value");
    }

    @Test
    void inlinesCursorBindAfterDoubleQuotedLiteralWithEscapedQuote() {
        // inlineCursorBinds double-quote branch must honor MySQL backslash-escapes (\") and "" doubling
        // symmetrically with the single-quote branch: a '?' after an escaped quote inside a
        // double-quoted literal is a real bind (inlined as the local NAME), and a literal '?' inside the
        // literal is left untouched. Here the literal contains an escaped quote and a literal '?'.
        RawCursorStatement node = new RawCursorStatement(
                List.of("v_amount"),
                new RawSql(
                        "SELECT amount FROM t WHERE note = \"a\\\"b?c\" AND id = ?",
                        List.of("p_id"),
                        "MYSQL"),
                new Block(List.of(), List.of(), List.of()),
                null);

        String emitted = new MySqlEmitter().visitRawCursorStatement(node);

        // The '?' inside the double-quoted literal stays literal; only the trailing bind '?' becomes the
        // routine-local identifier p_id.
        assertTrue(emitted.contains(
                "CURSOR FOR SELECT amount FROM t WHERE note = \"a\\\"b?c\" AND id = p_id;"),
                emitted);
    }

    @Test
    void stripsSingleTrailingSemicolonFromRawReadSource() {
        // Input hygiene: a single trailing ';' must not produce a double terminator in static SQL.
        RawReadIntoStatement node = new RawReadIntoStatement(
                List.of("v_balance"),
                new RawSql("SELECT balance FROM accounts WHERE id = ?;", List.of("p_id"), "MYSQL"));

        String emitted = new MySqlEmitter().visitRawReadIntoStatement(node);

        assertTrue(emitted.contains("SELECT balance INTO v_balance FROM accounts WHERE id = p_id;"), emitted);
        assertFalse(emitted.contains(";;"), emitted);
    }

    @Test
    void stripsSingleTrailingSemicolonFromRawCursorSource() {
        // Input hygiene: a single trailing ';' must not produce 'CURSOR FOR SELECT ...;;' (double
        // terminator) in the static cursor text.
        RawCursorStatement node = new RawCursorStatement(
                List.of("v_amount"),
                new RawSql("SELECT amount FROM invoices WHERE customer_id = ? ;", List.of("p_cid"), "MYSQL"),
                new Block(List.of(), List.of(), List.of()),
                null);

        String emitted = new MySqlEmitter().visitRawCursorStatement(node);

        assertTrue(emitted.contains(
                "CURSOR FOR SELECT amount FROM invoices WHERE customer_id = p_cid;"),
                emitted);
        assertFalse(emitted.contains(";;"), emitted);
    }

    @Test
    void emitsTryFinallyWithUnhandledExceptionReraisePattern() {
        Block body = new Block(
                List.of(),
                List.of(new TryCatchFinallyStatement(
                        new Block(List.of(), List.of(new RaiseStatement("45000", new LiteralExpression("boom", new TTextType()), List.of())), List.of()),
                        List.of(),
                        new Block(List.of(), List.of(new CloseCursorStatement("cur_items"), new CallStatement("cleanup", List.of())), List.of()))),
                List.of());

        String sql = new MySqlEmitter().emitProcedure("app", "with_finally", SecurityMode.INVOKER, body);

        // E-5: EXIT handler — a CONTINUE handler would resume at the next try-body statement.
        assertTrue(sql.contains("DECLARE EXIT HANDLER FOR SQLEXCEPTION"));
        assertTrue(sql.contains("GET DIAGNOSTICS CONDITION 1 __titan_saved_state = RETURNED_SQLSTATE, __titan_saved_message = MESSAGE_TEXT;"));
        assertTrue(sql.contains("CLOSE cur_items;"));
        assertTrue(sql.contains("CALL `cleanup`();"));
        assertTrue(sql.contains("SET __titan_saved_message = IFNULL(__titan_saved_message, 'Unhandled exception in try block');"));
        assertTrue(sql.contains("SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = __titan_saved_message;"));
        assertFalse(sql.contains("SIGNAL SQLSTATE IFNULL"));
    }

    @Test
    void emitsDedicatedSqlStateHandlersForMultiCatchDispatch() {
        TryCatchFinallyStatement tryCatchFinally = new TryCatchFinallyStatement(
                new Block(List.of(), List.of(new RaiseStatement("22012", new LiteralExpression("division by zero", new TTextType()), List.of())), List.of()),
                List.of(new CatchClause(
                        "ex",
                        "ArithmeticException | NullPointerException",
                        List.of("22012", "45001"),
                        new Block(List.of(), List.of(new CallStatement("handle_error", List.of())), List.of()))),
                null);

        String emitted = new MySqlEmitter().visitTryCatchFinallyStatement(tryCatchFinally);

        assertTrue(emitted.contains("DECLARE ex TEXT DEFAULT NULL;"));
        // E-5: EXIT handlers — CONTINUE would let try-body statements after the failure run.
        assertTrue(emitted.contains("DECLARE EXIT HANDLER FOR SQLSTATE '22012'"));
        assertTrue(emitted.contains("DECLARE EXIT HANDLER FOR SQLSTATE '45001'"));
        assertTrue(emitted.contains("SET __titan_saved_state = '22012';"));
        assertTrue(emitted.contains("SET __titan_saved_state = '45001';"));
        assertTrue(emitted.contains("SET ex = __titan_saved_message;"));
    }

    @Test
    void emitsMySqlStringTranslationHelperExpressions() {
        MySqlEmitter emitter = new MySqlEmitter();

        String concatSql = emitter.visitFunctionCallExpression(new FunctionCallExpression(
                "__titan_str_concat",
                List.of(new VariableRefExpression("v_left"), new VariableRefExpression("v_right")),
                null));
        String indexOfSql = emitter.visitFunctionCallExpression(new FunctionCallExpression(
                "__titan_str_index_of",
                List.of(new VariableRefExpression("v_text"), new LiteralExpression("x", new TTextType())),
                null));
        String substringSql = emitter.visitFunctionCallExpression(new FunctionCallExpression(
                "__titan_str_substring",
                List.of(new VariableRefExpression("v_text"), new LiteralExpression(1, new TIntType()), new LiteralExpression(4, new TIntType())),
                null));
        String foldedSubstringSql = emitter.visitFunctionCallExpression(new FunctionCallExpression(
                "__titan_str_substring",
                List.of(
                        new VariableRefExpression("v_text"),
                        new BinaryOpExpression(new LiteralExpression(1, new TIntType()), BinaryOperator.ADD, new LiteralExpression(1, new TIntType())),
                        new BinaryOpExpression(new LiteralExpression(10, new TIntType()), BinaryOperator.SUBTRACT, new LiteralExpression(5, new TIntType()))),
                null));
        String splitSql = emitter.visitFunctionCallExpression(new FunctionCallExpression(
                "__titan_str_split",
                List.of(new VariableRefExpression("v_text"), new LiteralExpression(",", new TTextType())),
                null));
        String matchesSql = emitter.visitFunctionCallExpression(new FunctionCallExpression(
                "__titan_str_matches",
                List.of(new VariableRefExpression("v_text"), new LiteralExpression("^[a-z]+$", new TTextType())),
                null));
        String stringValueOfSql = emitter.visitCastExpression(new CastExpression(
                new VariableRefExpression("v_num"),
                new TTextType()));

        assertTrue(concatSql.equals("CONCAT(COALESCE(CAST(v_left AS CHAR), 'null'), COALESCE(CAST(v_right AS CHAR), 'null'))"));
        assertTrue(indexOfSql.contains("LOCATE('x', v_text) - 1"));
        assertTrue(substringSql.contains("SUBSTRING(v_text, 2, 3)"));
        assertTrue(foldedSubstringSql.contains("SUBSTRING(v_text, 3, 3)"));
        assertTrue(splitSql.equals("JSON_EXTRACT(CONCAT('[\"', REPLACE(v_text, ',', '\",\"'), '\"]'), '$')"));
        assertTrue(matchesSql.equals("(v_text REGEXP '^[a-z]+$')"));
        assertTrue(stringValueOfSql.equals("CAST(v_num AS CHAR)"));
    }

    /**
     * B-10 (TG-BLK-012): MySQL's BOOLEAN is TINYINT, so a boolean reaching a text context must be
     * coerced to 'true'/'false' — a bare CAST AS CHAR renders '1'/'0'. The coercion is applied at
     * every boolean-to-text site (str_concat operands, String.valueOf(boolean) casts, static-set
     * writes) and fires only for syntactically-boolean TIR nodes. The NULL arm matches Java/PG
     * 'null' rendering of a NULL boolean (MySQL IF(NULL,...) would otherwise return 'false').
     */
    @Test
    void coercesBooleanOperandsToTrueFalseTextForMySql() {
        MySqlEmitter emitter = new MySqlEmitter();

        // String.valueOf(boolean): a cast to text over a boolean predicate must coerce via CASE.
        String valueOfBoolean = emitter.visitCastExpression(new CastExpression(
                new BinaryOpExpression(
                        new VariableRefExpression("v_left"),
                        BinaryOperator.LESS_THAN_OR_EQUAL,
                        new VariableRefExpression("v_right")),
                new TTextType()));
        assertEquals(
                "(CASE WHEN (v_left <= v_right) IS NULL THEN NULL WHEN (v_left <= v_right) THEN 'true' ELSE 'false' END)",
                valueOfBoolean);

        // A boolean literal in a String + concatenation chain.
        String concatWithBoolean = emitter.visitFunctionCallExpression(new FunctionCallExpression(
                "__titan_str_concat",
                List.of(
                        new LiteralExpression("flag=", new TTextType()),
                        new LiteralExpression(true, new TBooleanType())),
                null));
        assertEquals(
                "CONCAT(COALESCE(CAST('flag=' AS CHAR), 'null'), "
                        + "COALESCE((CASE WHEN TRUE IS NULL THEN NULL WHEN TRUE THEN 'true' ELSE 'false' END), 'null'))",
                concatWithBoolean);

        // A NOT predicate reaching text renders through the same coercion.
        String notToText = emitter.visitCastExpression(new CastExpression(
                new NotExpression(new VariableRefExpression("v_done")),
                new TTextType()));
        assertEquals(
                "(CASE WHEN (NOT v_done) IS NULL THEN NULL WHEN (NOT v_done) THEN 'true' ELSE 'false' END)",
                notToText);

        // A call to a boolean-returning generated routine (the consumer's pageInfo.hasNextPage
        // path) coerces via CASE once its return type is registered.
        emitter.useRoutineReturnTypes(java.util.Map.of("has_more", new TBooleanType()));
        String routineCallToText = emitter.visitFunctionCallExpression(new FunctionCallExpression(
                "__titan_str_concat",
                List.of(
                        new LiteralExpression("hasMore=", new TTextType()),
                        new FunctionCallExpression("has_more",
                                List.of(new VariableRefExpression("v_count")), null)),
                null));
        assertEquals(
                "CONCAT(COALESCE(CAST('hasMore=' AS CHAR), 'null'), "
                        + "COALESCE((CASE WHEN has_more(v_count) IS NULL THEN NULL "
                        + "WHEN has_more(v_count) THEN 'true' ELSE 'false' END), 'null'))",
                routineCallToText);

        // Non-boolean operands are unaffected: a plain CAST AS CHAR, never the boolean coercion.
        String numberToText = emitter.visitCastExpression(new CastExpression(
                new VariableRefExpression("v_num"),
                new TTextType()));
        assertEquals("CAST(v_num AS CHAR)", numberToText);
        assertFalse(numberToText.contains("'true'"));
    }

    @Test
    void rejectsStringFormatIntrinsicForMySql() {
        UnsupportedOperationException error = assertThrows(
                UnsupportedOperationException.class,
                () -> new MySqlEmitter().visitFunctionCallExpression(new FunctionCallExpression(
                        "__titan_str_format",
                        List.of(
                                new LiteralExpression("%s-%s", new TTextType()),
                                new VariableRefExpression("v_text"),
                                new LiteralExpression("ok", new TTextType())),
                        null)));

        assertTrue(error.getMessage().contains("TITAN-E001"));
        assertTrue(error.getMessage().contains("String.format"));
        assertTrue(error.getMessage().contains("MySQL"));
    }

    @Test
    void rejectsUnrecognizedTitanIntrinsicsForMySql() {
        UnsupportedOperationException error = assertThrows(
                UnsupportedOperationException.class,
                () -> new MySqlEmitter().visitFunctionCallExpression(new FunctionCallExpression(
                        "__titan_optional_throw",
                        List.of(),
                        null)));

        assertTrue(error.getMessage().contains("TITAN-E001"));
        assertTrue(error.getMessage().contains("__titan_optional_throw"));
        assertTrue(error.getMessage().contains("MySQL"));
    }

    @Test
    void emitsMySqlCharCodePositionalSearchAndNullSafeEqualsIntrinsics() {
        MySqlEmitter emitter = new MySqlEmitter();

        String charCodeSql = emitter.visitFunctionCallExpression(new FunctionCallExpression(
                "__titan_char_code",
                List.of(new VariableRefExpression("v_ch")),
                null));
        String base64UrlEncodeSql = emitter.visitFunctionCallExpression(new FunctionCallExpression(
                "__titan_text_base64url_encode_utf8",
                List.of(new VariableRefExpression("v_text")),
                null));
        String base64UrlDecodeSql = emitter.visitFunctionCallExpression(new FunctionCallExpression(
                "__titan_text_base64url_decode_utf8",
                List.of(new VariableRefExpression("v_base64")),
                null));
        String base64UrlAlphabetIndexSql = emitter.visitFunctionCallExpression(new FunctionCallExpression(
                "__titan_text_base64url_alphabet_index",
                List.of(new VariableRefExpression("v_char")),
                null));
        String indexOfFromSql = emitter.visitFunctionCallExpression(new FunctionCallExpression(
                "__titan_str_index_of",
                List.of(
                        new VariableRefExpression("v_text"),
                        new LiteralExpression("x", new TTextType()),
                        new VariableRefExpression("v_from")),
                null));
        String startsWithOffsetSql = emitter.visitFunctionCallExpression(new FunctionCallExpression(
                "__titan_str_starts_with",
                List.of(
                        new VariableRefExpression("v_text"),
                        new LiteralExpression("pre", new TTextType()),
                        new VariableRefExpression("v_offset")),
                null));
        String nullSafeEqualsSql = emitter.visitFunctionCallExpression(new FunctionCallExpression(
                "equals",
                List.of(new VariableRefExpression("v_left"), new VariableRefExpression("v_right")),
                null));
        String stringEqualsSql = emitter.visitFunctionCallExpression(new FunctionCallExpression(
                "__titan_str_equals",
                List.of(new VariableRefExpression("v_left"), new VariableRefExpression("v_right")),
                null));

        assertEquals("ASCII(v_ch)", charCodeSql);
        assertEquals("REPLACE(REPLACE(REPLACE(REPLACE(TO_BASE64(CONVERT(v_text USING utf8mb4)), '=', ''), '+', '-'), '/', '_'), CHAR(10), '')",
                base64UrlEncodeSql);
        assertEquals("CONVERT(FROM_BASE64(CONCAT(REPLACE(REPLACE(v_base64, '-', '+'), '_', '/'), REPEAT('=', MOD(4 - MOD(CHAR_LENGTH(v_base64), 4), 4)))) USING utf8mb4)",
                base64UrlDecodeSql);
        assertEquals("(LOCATE(BINARY v_char, BINARY 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_') - 1)",
                base64UrlAlphabetIndexSql);
        assertEquals(
                "(CASE WHEN 'x' = '' THEN LEAST(GREATEST(v_from, 0), CHAR_LENGTH(v_text))"
                        + " WHEN GREATEST(v_from, 0) > CHAR_LENGTH(v_text) THEN -1"
                        + " WHEN LOCATE('x', v_text, GREATEST(v_from, 0) + 1) = 0 THEN -1"
                        + " ELSE (LOCATE('x', v_text, GREATEST(v_from, 0) + 1) - 1) END)",
                indexOfFromSql);
        assertEquals(
                "(CASE WHEN v_offset < 0 THEN FALSE"
                        + " WHEN (v_offset + CHAR_LENGTH('pre')) > CHAR_LENGTH(v_text) THEN FALSE"
                        + " ELSE SUBSTRING(v_text, v_offset + 1, CHAR_LENGTH('pre')) = 'pre' END)",
                startsWithOffsetSql);
        assertEquals("(v_left <=> v_right)", nullSafeEqualsSql);
        assertEquals("(BINARY v_left = BINARY v_right)", stringEqualsSql);
    }

    @Test
    void emitsMySqlMathTranslationHelperExpressions() {
        MySqlEmitter emitter = new MySqlEmitter();

        String randomSql = emitter.visitFunctionCallExpression(new FunctionCallExpression("__titan_math_random", List.of(), null));
        String log10Sql = emitter.visitFunctionCallExpression(new FunctionCallExpression(
                "__titan_math_log10",
                List.of(new VariableRefExpression("v_num")),
                null));
        String roundSql = emitter.visitFunctionCallExpression(new FunctionCallExpression(
                "__titan_math_round",
                List.of(new VariableRefExpression("v_num")),
                null));

        assertTrue(randomSql.equals("RAND()"));
        assertTrue(log10Sql.equals("LOG10(v_num)"));
        assertTrue(roundSql.equals("titan_rt_java_round(v_num, 0)"));
    }

    @Test
    void emitsMySqlDateTimeTranslationHelperExpressions() {
        MySqlEmitter emitter = new MySqlEmitter();

        String nowDate = emitter.visitFunctionCallExpression(new FunctionCallExpression("__titan_time_localdate_now", List.of(), null));
        String plusDays = emitter.visitFunctionCallExpression(new FunctionCallExpression(
                "__titan_time_plus_days",
                List.of(new VariableRefExpression("v_date"), new LiteralExpression(2, new TIntType())),
                null));
        String toDate = emitter.visitFunctionCallExpression(new FunctionCallExpression(
                "__titan_time_to_local_date",
                List.of(new VariableRefExpression("v_ts")),
                null));
        String instantNow = emitter.visitFunctionCallExpression(new FunctionCallExpression("__titan_time_instant_now", List.of(), null));
        String instantOfEpochMillis = emitter.visitFunctionCallExpression(new FunctionCallExpression(
                "__titan_time_instant_of_epoch_millis",
                List.of(new VariableRefExpression("v_epoch_millis")),
                null));
        String zonedNow = emitter.visitFunctionCallExpression(new FunctionCallExpression("__titan_time_zoneddatetime_now", List.of(), null));
        String toInstant = emitter.visitFunctionCallExpression(new FunctionCallExpression(
                "__titan_time_to_instant",
                List.of(new VariableRefExpression("v_ts")),
                null));
        String duration = emitter.visitFunctionCallExpression(new FunctionCallExpression(
                "__titan_time_duration_of_seconds",
                List.of(new LiteralExpression(90, new TIntType())),
                null));
        String period = emitter.visitFunctionCallExpression(new FunctionCallExpression(
                "__titan_time_period_of_days",
                List.of(new LiteralExpression(2, new TIntType())),
                null));
        String minusAmount = emitter.visitFunctionCallExpression(new FunctionCallExpression(
                "__titan_time_minus_amount",
                List.of(
                        new VariableRefExpression("v_ts"),
                        new FunctionCallExpression("__titan_time_period_of_days", List.of(new LiteralExpression(1, new TIntType())), null)
                ),
                null));

        assertTrue(nowDate.equals("CURRENT_DATE"));
        assertTrue(plusDays.equals("DATE_ADD(v_date, INTERVAL 2 DAY)"));
        assertTrue(toDate.equals("DATE(v_ts)"));
        assertTrue(instantNow.equals("UTC_TIMESTAMP()"));
        assertTrue(instantOfEpochMillis.equals("TIMESTAMPADD(MICROSECOND, (v_epoch_millis * 1000), '1970-01-01 00:00:00')"));
        assertTrue(zonedNow.equals("CONVERT_TZ(UTC_TIMESTAMP(), '+00:00', @@session.time_zone)"));
        assertTrue(toInstant.equals("CONVERT_TZ(v_ts, @@session.time_zone, '+00:00')"));
        assertTrue(duration.equals("INTERVAL 90 SECOND"));
        assertTrue(period.equals("INTERVAL 2 DAY"));
        assertTrue(minusAmount.equals("DATE_SUB(v_ts, INTERVAL 1 DAY)"));
    }

    @Test
    void emitsMySqlTemporalAmountLocalsAsMagnitudeBackedIntervals() {
        Block body = new Block(
                List.of(
                        new DeclareVariable(
                                "timeout",
                                new TDurationType(),
                                false,
                                new FunctionCallExpression(
                                        "__titan_time_duration_of_seconds",
                                        List.of(new LiteralExpression(90, new TIntType())),
                                        null)),
                        new DeclareVariable(
                                "now",
                                new TTimestampType(),
                                false,
                                new FunctionCallExpression("__titan_time_localdatetime_now", List.of(), null)),
                        new DeclareVariable(
                                "later",
                                new TTimestampType(),
                                false,
                                new FunctionCallExpression(
                                        "__titan_time_plus_amount",
                                        List.of(new VariableRefExpression("now"), new VariableRefExpression("timeout")),
                                        null))
                ),
                List.of(),
                List.of());

        String sql = new MySqlEmitter().emitProcedure("app", "temporal_locals", SecurityMode.INVOKER, body);

        assertTrue(sql.contains("DECLARE v_timeout BIGINT DEFAULT 90;"));
        assertTrue(sql.contains("DECLARE v_now DATETIME(6) DEFAULT CURRENT_TIMESTAMP;"));
        assertTrue(sql.contains("DECLARE v_later DATETIME(6) DEFAULT DATE_ADD(v_now, INTERVAL v_timeout SECOND);"));
    }

    @Test
    void normalizesDerivedAndReassignedMySqlDurationLocalsToSecondsWhenNeeded() {
        String copySql = new MySqlEmitter().emitFunction(
                "app",
                "copy_timeout",
                SecurityMode.INVOKER,
                new TDurationType(),
                new Block(
                        List.of(
                                new DeclareVariable(
                                        "timeout",
                                        new TDurationType(),
                                        false,
                                        new FunctionCallExpression(
                                                "__titan_time_duration_of_minutes",
                                                List.of(new LiteralExpression(5, new TIntType())),
                                                null)),
                                new DeclareVariable(
                                        "adjusted",
                                        new TDurationType(),
                                        false,
                                        new VariableRefExpression("timeout"))),
                        List.of(new ReturnStatement(new VariableRefExpression("adjusted"))),
                        List.of()),
                List.of());

        assertTrue(copySql.contains("DECLARE v_timeout BIGINT DEFAULT 5;"));
        assertTrue(copySql.contains("DECLARE v_adjusted BIGINT DEFAULT (v_timeout * 60);"));
        assertTrue(copySql.contains("SET __titan_return_value = v_adjusted;"));
        assertTrue(copySql.contains("RETURN __titan_return_value;"));

        String reassignSql = new MySqlEmitter().emitFunction(
                "app",
                "apply_timeout_after_reassign",
                SecurityMode.INVOKER,
                new TTimestampType(),
                new Block(
                        List.of(
                                new DeclareVariable(
                                        "adjusted",
                                        new TDurationType(),
                                        false,
                                        new FunctionCallExpression(
                                                "__titan_time_duration_of_minutes",
                                                List.of(new LiteralExpression(5, new TIntType())),
                                                null))),
                        List.of(
                                new Assign(new VariableRefExpression("adjusted"), new VariableRefExpression("timeout")),
                                new ReturnStatement(new FunctionCallExpression(
                                        "__titan_time_plus_amount",
                                        List.of(new VariableRefExpression("input"), new VariableRefExpression("adjusted")),
                                        null))),
                        List.of()),
                List.of(
                        new RoutineParameter("input", "p_input", new TTimestampType()),
                        new RoutineParameter("timeout", "p_timeout", new TDurationType())));

        assertTrue(reassignSql.contains("DECLARE v_adjusted BIGINT DEFAULT 5;"));
        assertTrue(reassignSql.contains("SET v_adjusted = p_timeout;"));
        assertTrue(reassignSql.contains("SET __titan_return_value = DATE_ADD(p_input, INTERVAL v_adjusted SECOND);"));
        assertTrue(reassignSql.contains("RETURN __titan_return_value;"));
    }

    @Test
    void emitsMySqlDurationRoutineBoundariesAndRejectsPeriodBoundaries() {
        String applyTimeoutSql = new MySqlEmitter().emitFunction(
                "app",
                "apply_timeout",
                SecurityMode.INVOKER,
                new TTimestampType(),
                new Block(
                        List.of(),
                        List.of(new ReturnStatement(new FunctionCallExpression(
                                "__titan_time_plus_amount",
                                List.of(new VariableRefExpression("input"), new VariableRefExpression("timeout")),
                                null))),
                        List.of()),
                        List.of(
                        new RoutineParameter("input", "p_input", new TTimestampType()),
                        new RoutineParameter("timeout", "p_timeout", new TDurationType())));

        assertTrue(applyTimeoutSql.contains("CREATE FUNCTION `app`.`apply_timeout`(p_input DATETIME(6), p_timeout BIGINT)"));
        assertTrue(applyTimeoutSql.contains("RETURNS DATETIME(6)"));
        assertTrue(applyTimeoutSql.contains("SET __titan_return_value = DATE_ADD(p_input, INTERVAL p_timeout SECOND);"));
        assertTrue(applyTimeoutSql.contains("RETURN __titan_return_value;"));

        String defaultTimeoutSql = new MySqlEmitter().emitFunction(
                "app",
                "default_timeout",
                SecurityMode.INVOKER,
                new TDurationType(),
                new Block(
                        List.of(),
                        List.of(new ReturnStatement(new FunctionCallExpression(
                                "__titan_time_duration_of_minutes",
                                List.of(new LiteralExpression(5, new TIntType())),
                                null))),
                        List.of()),
                List.of());

        assertTrue(defaultTimeoutSql.contains("CREATE FUNCTION `app`.`default_timeout`()"));
        assertTrue(defaultTimeoutSql.contains("RETURNS BIGINT"));
        assertTrue(defaultTimeoutSql.contains("SET __titan_return_value = (5 * 60);"));
        assertTrue(defaultTimeoutSql.contains("RETURN __titan_return_value;"));

        IllegalArgumentException parameterError = assertThrows(IllegalArgumentException.class, () ->
                new MySqlEmitter().emitProcedure(
                        "app",
                        "apply_grace",
                        SecurityMode.INVOKER,
                        new Block(List.of(), List.of(), List.of()),
                        List.of(new RoutineParameter("grace", "p_grace", new TPeriodType()))));
        assertTrue(parameterError.getMessage().contains("cannot expose period-like parameter"));

        IllegalArgumentException returnError = assertThrows(IllegalArgumentException.class, () ->
                new MySqlEmitter().emitFunction(
                        "app",
                        "default_grace",
                        SecurityMode.INVOKER,
                        new TPeriodType(),
                        new Block(List.of(), List.of(new ReturnStatement(new LiteralExpression(0, new TIntType()))), List.of()),
                        List.of()));
        assertTrue(returnError.getMessage().contains("cannot return a period-like value"));
    }

    @Test
    void emitsMySqlSessionTimezonePreamble() {
        Block body = new Block(List.of(), List.of(), List.of());
        String sql = new MySqlEmitter().emitProcedure("app", "tz_proc", SecurityMode.INVOKER, body);
        assertTrue(sql.contains("SET time_zone = '+00:00';"));
    }

    @Test
    void emitsForEachViaTempTableAndCursorPattern() {
        Block body = new Block(
                List.of(new DeclareVariable("value", new TIntType(), false, null)),
                List.of(new ForEachStatement(
                        "value",
                        new TIntType(),
                        new VariableRefExpression("values_json"),
                        new Block(List.of(), List.of(new CallStatement("consume", List.of(new VariableRefExpression("value")))), List.of()),
                        null)),
                List.of());

        String sql = new MySqlEmitter().emitProcedure("app", "foreach_values", SecurityMode.INVOKER, body);

        assertTrue(sql.contains("CREATE TEMPORARY TABLE titan_iter_v_value (value INT);"));
        assertTrue(sql.contains("JSON_TABLE(values_json, '$[*]' COLUMNS (value INT PATH '$'))"));
        assertTrue(sql.contains("DECLARE cur_v_value CURSOR FOR SELECT value FROM titan_iter_v_value;"));
        assertTrue(sql.contains("DECLARE EXIT HANDLER FOR SQLEXCEPTION"));
        assertTrue(sql.contains("IF cursor_open_v_value THEN CLOSE cur_v_value; END IF;"));
        assertTrue(sql.contains("FETCH cur_v_value INTO v_value;"));
        assertTrue(sql.contains("CALL `consume`(v_value);"));
        assertTrue(sql.contains("DROP TEMPORARY TABLE IF EXISTS titan_iter_v_value;"));
    }

    @Test
    void renamesGeneratedForEachHelpersWhenTheyCollideWithUserLocals() {
        Block body = new Block(
                List.of(
                        new DeclareVariable("value", new TIntType(), false, null),
                        new DeclareVariable("done_value", new TBooleanType(), false, new LiteralExpression(false, new TBooleanType())),
                        new DeclareVariable("cursor_open_value", new TBooleanType(), false, new LiteralExpression(false, new TBooleanType())),
                        new DeclareVariable("cur_value", new TIntType(), false, new LiteralExpression(0, new TIntType())),
                        new DeclareVariable("titan_iter_value", new TTextType(), true, new LiteralExpression("seed", new TTextType())),
                        new DeclareVariable("iter_value", new TIntType(), false, new LiteralExpression(1, new TIntType()))),
                List.of(new ForEachStatement(
                        "value",
                        new TIntType(),
                        new VariableRefExpression("values_json"),
                        new Block(List.of(), List.of(new CallStatement("consume", List.of(new VariableRefExpression("value")))), List.of()),
                        null)),
                List.of());

        String sql = new MySqlEmitter().emitProcedure("app", "foreach_values", SecurityMode.INVOKER, body);

        assertTrue(sql.contains("DECLARE v_done_value BOOLEAN DEFAULT FALSE;"));
        assertTrue(sql.contains("DECLARE v_cursor_open_value BOOLEAN DEFAULT FALSE;"));
        assertTrue(sql.contains("DECLARE v_cur_value INT DEFAULT 0;"));
        assertTrue(sql.contains("DECLARE v_titan_iter_value TEXT DEFAULT 'seed';"));
        assertTrue(sql.contains("DECLARE v_iter_value INT DEFAULT 1;"));

        assertTrue(sql.contains("CREATE TEMPORARY TABLE titan_iter_v_value (value INT);"));
        assertTrue(sql.contains("DECLARE cur_v_value CURSOR FOR SELECT value FROM titan_iter_v_value;"));
        assertTrue(sql.contains("DECLARE done_v_value BOOLEAN DEFAULT FALSE;"));
        assertTrue(sql.contains("DECLARE cursor_open_v_value BOOLEAN DEFAULT FALSE;"));
        assertTrue(sql.contains("IF cursor_open_v_value THEN CLOSE cur_v_value; END IF;"));
        assertTrue(sql.contains("iter_v_value: LOOP"));
        assertTrue(sql.contains("FETCH cur_v_value INTO v_value;"));
        assertTrue(sql.contains("DROP TEMPORARY TABLE IF EXISTS titan_iter_v_value;"));
    }

    @Test
    void emitsMySqlViewWithExplicitInvokerSecurity() {
        String sql = new MySqlEmitter().emitView("app", "active_accounts", "SELECT * FROM accounts WHERE active = TRUE");

        assertTrue(sql.contains("DROP VIEW IF EXISTS `app`.`active_accounts`;"));
        assertTrue(sql.contains("CREATE SQL SECURITY INVOKER VIEW `app`.`active_accounts` AS"));
        assertTrue(sql.contains("SELECT * FROM accounts WHERE active = TRUE;"));
    }

    @Test
    void emitsMySqlTriggerInlineBodiesPerEvent() {
        Block body = new Block(
                List.of(new DeclareVariable("v_count", new TIntType(), false, new LiteralExpression(0, new TIntType()))),
                List.of(new Assign(new VariableRefExpression("v_count"), new LiteralExpression(1, new TIntType()))),
                List.of());

        String sql = new MySqlEmitter().emitTrigger(
                "app",
                "before_account",
                SecurityMode.INVOKER,
                new TriggerSpec("accounts", "BEFORE", List.of("INSERT", "UPDATE"), "ROW"),
                body);

        assertTrue(sql.contains("DELIMITER $$"));
        assertTrue(sql.contains("DROP TRIGGER IF EXISTS `before_account_insert_trg`$$"));
        assertTrue(sql.contains("-- TITAN-W004: MySQL triggers do not support SQL SECURITY INVOKER; emitting DEFINER trigger owned by CURRENT_USER."));
        assertTrue(sql.contains("CREATE DEFINER = CURRENT_USER TRIGGER `before_account_insert_trg`"));
        assertTrue(sql.contains("BEFORE INSERT ON `app`.`accounts`"));
        assertTrue(sql.contains("CREATE DEFINER = CURRENT_USER TRIGGER `before_account_update_trg`"));
        assertTrue(sql.contains("BEFORE UPDATE ON `app`.`accounts`"));
        assertTrue(sql.contains("DECLARE v_count INT DEFAULT 0;"));
        assertTrue(sql.contains("SET v_count = 1;"));
        assertTrue(sql.contains("DELIMITER ;"));
    }

    @Test
    void emitsMySqlTriggerWithoutInvokerWarningWhenSecurityDefinerRequested() {
        Block body = new Block(List.of(), List.of(), List.of());

        String sql = new MySqlEmitter().emitTrigger(
                "app",
                "before_account_definer",
                SecurityMode.DEFINER,
                new TriggerSpec("accounts", "BEFORE", List.of("INSERT"), "ROW"),
                body);

        assertFalse(sql.contains("TITAN-W004"));
        assertTrue(sql.contains("CREATE DEFINER = CURRENT_USER TRIGGER `before_account_definer_insert_trg`"));
    }

    @Test
    void emitsMySqlStaticResetCleanupForTriggerBodies() {
        Block body = new Block(
                List.of(),
                List.of(new CallStatement("__titan_static_set", List.of(
                        new LiteralExpression("Demo#counter", new TTextType()),
                        new LiteralExpression(1, new TIntType())))),
                List.of());

        String sql = new MySqlEmitter().emitTrigger(
                "app",
                "before_account_static",
                SecurityMode.INVOKER,
                new TriggerSpec("accounts", "BEFORE", List.of("INSERT"), "ROW"),
                body);

        assertTrue(sql.contains("DECLARE EXIT HANDLER FOR SQLEXCEPTION"));
        assertTrue(sql.contains("SET @__titan_static_reset = titan_rt_static_reset('Demo#counter');"));
    }

    @Test
    void emitsScheduledJobAsCreateEvent() {
        Block body = new Block(List.of(), List.of(new ReturnStatement(null)), List.of());

        String sql = new MySqlEmitter().emitScheduledJob(
                "app",
                "refresh_rollups",
                SecurityMode.INVOKER,
                new ScheduledJobSpec("15 4 * * *", "nightly_refresh"),
                body);

        assertTrue(sql.contains("CREATE PROCEDURE `app`.`refresh_rollups`()"));
        assertTrue(sql.contains("DROP EVENT IF EXISTS `nightly_refresh`;"));
        assertTrue(sql.contains("CREATE EVENT `nightly_refresh`"));
        assertTrue(sql.contains("ON SCHEDULE EVERY 1 DAY STARTS TIMESTAMP(CURRENT_DATE, MAKETIME(4, 15, 0))"));
        assertTrue(sql.contains("DO CALL `app`.`refresh_rollups`();"));
    }

    @Test
    void emitsMySqlEventEveryMinuteForEveryMinuteCron() {
        Block body = new Block(List.of(), List.of(new ReturnStatement(null)), List.of());

        String sql = new MySqlEmitter().emitScheduledJob(
                "app",
                "heartbeat_job",
                SecurityMode.INVOKER,
                new ScheduledJobSpec("* * * * *", "heartbeat_event"),
                body);

        assertTrue(sql.contains("CREATE EVENT `heartbeat_event`"));
        assertTrue(sql.contains("ON SCHEDULE EVERY 1 MINUTE STARTS CURRENT_TIMESTAMP"));
    }

    @Test
    void emitsMySqlEventEveryNMinutesForStepCron() {
        Block body = new Block(List.of(), List.of(new ReturnStatement(null)), List.of());

        String sql = new MySqlEmitter().emitScheduledJob(
                "app",
                "pulse_job",
                SecurityMode.INVOKER,
                new ScheduledJobSpec("*/15 * * * *", "pulse_event"),
                body);

        assertTrue(sql.contains("CREATE EVENT `pulse_event`"));
        assertTrue(sql.contains("ON SCHEDULE EVERY 15 MINUTE STARTS CURRENT_TIMESTAMP"));
        assertFalse(sql.contains("Cron expression cannot be represented natively by MySQL events"));
    }

    @Test
    void emitsFallbackWarningForUnmappableMySqlCronExpression() {
        Block body = new Block(List.of(), List.of(new ReturnStatement(null)), List.of());

        String sql = new MySqlEmitter().emitScheduledJob(
                "app",
                "weekday_job",
                SecurityMode.INVOKER,
                new ScheduledJobSpec("0 9 * * MON-FRI", "weekday_event"),
                body);

        assertTrue(sql.contains("-- Cron expression '0 9 * * MON-FRI' cannot be represented natively by MySQL events; "
                + "approximated as EVERY 1 DAY starting CURRENT_TIMESTAMP "
                + "(day-of-week/day-of-month/month constraints are not enforced)."));
        assertTrue(sql.contains("ON SCHEDULE EVERY 1 DAY STARTS CURRENT_TIMESTAMP"));
    }

    @Test
    void rejectsInvalidCronBeforeEmittingMySqlScheduledJob() {
        Block body = new Block(List.of(), List.of(new ReturnStatement(null)), List.of());

        assertThrows(IllegalArgumentException.class, () -> new MySqlEmitter().emitScheduledJob(
                "app",
                "bad_job",
                SecurityMode.INVOKER,
                new ScheduledJobSpec("61 4 * * *", "bad_event"),
                body));

        assertThrows(IllegalArgumentException.class, () -> new MySqlEmitter().emitScheduledJob(
                "app",
                "bad_step_job",
                SecurityMode.INVOKER,
                new ScheduledJobSpec("*/70 * * * *", "bad_step_event"),
                body));
    }

    @Test
    void emitsMySqlSpecificDmlAndCursorPatterns() {
        InsertSql insertWithReturning = new InsertSql(
                "accounts",
                List.of("email", "active"),
                List.of(
                        new LiteralExpression("alice@example.com", new TTextType()),
                        new LiteralExpression(true, new TBooleanType())),
                null,
                new ConflictClause(
                        List.of("email"),
                        List.of(new SetClause("active", new LiteralExpression(false, new TBooleanType())))),
                List.of("id"));

        ForCursorStatement forCursor = new ForCursorStatement(
                "v_id",
                new SelectSql(
                        List.of(new SelectColumn(new ColumnRefExpression("accounts", "id"), null)),
                        "accounts",
                        List.of(),
                        null,
                        List.of(),
                        null,
                        List.of(),
                        null,
                        null,
                        null,
                        List.of()),
                new Block(
                        List.of(),
                        List.of(new Assign(
                                new VariableRefExpression("v_total"),
                                new BinaryOpExpression(
                                        new VariableRefExpression("v_total"),
                                        BinaryOperator.ADD,
                                        new LiteralExpression(1, new TIntType())))),
                        List.of()),
                null);

        Block body = new Block(
                List.of(new DeclareVariable("v_total", new TIntType(), false, new LiteralExpression(0, new TIntType()))),
                List.of(
                        new ExecuteSqlStatement(insertWithReturning),
                        forCursor,
                        new Assign(
                                new VariableRefExpression("v_message"),
                                new BinaryOpExpression(
                                        new LiteralExpression("hello ", new TTextType()),
                                        BinaryOperator.ADD,
                                        new LiteralExpression("world", new TTextType())))),
                List.of());

        String sql = new MySqlEmitter().emitProcedure("app", "process_dml", SecurityMode.INVOKER, body);

        assertTrue(sql.contains("INSERT INTO `accounts` (`email`, `active`) VALUES ('alice@example.com', TRUE) ON DUPLICATE KEY UPDATE `active` = FALSE; SELECT LAST_INSERT_ID() AS `id`;"));
        assertTrue(sql.contains("DECLARE cur_v_id CURSOR FOR SELECT `accounts`.`id` FROM `accounts`;"));
        assertTrue(sql.contains("DECLARE EXIT HANDLER FOR SQLEXCEPTION"));
        assertTrue(sql.contains("IF cursor_open_v_id THEN CLOSE cur_v_id; END IF;"));
        assertTrue(sql.contains("DECLARE CONTINUE HANDLER FOR NOT FOUND SET done_v_id = TRUE;"));
        assertTrue(sql.contains("FETCH cur_v_id INTO v_id;"));
        assertTrue(sql.contains("CONCAT('hello ', 'world')"));
    }

    @Test
    void emitsEmptyValuesClauseForInsertWithoutValuesOrSelectSource() {
        InsertSql insert = new InsertSql("audit_events", List.of(), List.of(), null, null, List.of());

        assertEquals("INSERT INTO `audit_events` VALUES ()", new MySqlEmitter().visitInsertSql(insert));
    }

    @Test
    void emitsNoOpDuplicateKeyUpdateForConflictDoNothing() {
        InsertSql insert = new InsertSql(
                "accounts",
                List.of("email", "active"),
                List.of(
                        new LiteralExpression("alice@example.com", new TTextType()),
                        new LiteralExpression(true, new TBooleanType())),
                null,
                new ConflictClause(List.of("email"), List.of()),
                List.of());

        assertEquals(
                "INSERT INTO `accounts` (`email`, `active`) VALUES ('alice@example.com', TRUE) ON DUPLICATE KEY UPDATE `email` = `email`",
                new MySqlEmitter().visitInsertSql(insert));
    }

    @Test
    void emitsExpressionValuedSetAsServerSideArithmeticInUpdateAndConflict() {
        // G3 (spike B3): an expression-valued SET right-hand side renders as a backtick-quoted,
        // table-qualified column self-reference plus arithmetic (`drafts`.`version` + 1), valid in
        // both plain UPDATE and ON DUPLICATE KEY UPDATE on MySQL.
        BinaryOpExpression bump = new BinaryOpExpression(
                new ColumnRefExpression("drafts", "version"),
                BinaryOperator.ADD,
                new LiteralExpression(1, new TIntType()));

        UpdateSql update = new UpdateSql(
                "drafts",
                List.of(new SetClause("version", bump)),
                new BinaryOpExpression(new ColumnRefExpression("drafts", "id"), BinaryOperator.EQUAL, new LiteralExpression(7, new TIntType())),
                List.of());

        InsertSql upsert = new InsertSql(
                "drafts",
                List.of("id", "version"),
                List.of(new LiteralExpression(7, new TIntType()), new LiteralExpression(1, new TIntType())),
                null,
                new ConflictClause(List.of("id"), List.of(new SetClause("version", bump))),
                List.of());

        MySqlEmitter emitter = new MySqlEmitter();

        assertEquals(
                "UPDATE `drafts` SET `version` = (`drafts`.`version` + 1) WHERE (`drafts`.`id` = 7)",
                emitter.visitUpdateSql(update));
        assertTrue(emitter.visitInsertSql(upsert)
                .contains("ON DUPLICATE KEY UPDATE `version` = (`drafts`.`version` + 1)"),
                emitter.visitInsertSql(upsert));
    }

    @Test
    void castsJsonColumnSetAndInsertValuesToJson() {
        // G4 (spike B4): a value targeting a SQLType.JSON column is lowered to a CAST to TJsonType,
        // which the MySQL emitter renders as CAST(... AS JSON). MySQL parses a JSON string
        // implicitly, but the explicit cast keeps the dialect correct and uniform with PG's
        // CAST(... AS JSONB).
        InsertSql insert = new InsertSql(
                "management_drafts",
                List.of("document"),
                List.of(new CastExpression(new VariableRefExpression("p_document"), new TJsonType())),
                null,
                new ConflictClause(
                        List.of("id"),
                        List.of(new SetClause("document", new CastExpression(new VariableRefExpression("p_document"), new TJsonType())))),
                List.of());

        String sql = new MySqlEmitter().visitInsertSql(insert);

        assertTrue(sql.contains("VALUES (CAST(p_document AS JSON))"), sql);
        assertTrue(sql.contains("ON DUPLICATE KEY UPDATE `document` = CAST(p_document AS JSON)"), sql);
    }

    @Test
    void keepsBareSelectStatementInMySqlProcedureBody() {
        // G1 asymmetry (spike B1): MySQL accepts a bare SELECT statement inside a procedure, so a
        // discarded forUpdate select stays a plain SELECT here (only PG rewrites it to PERFORM).
        SelectSql lockingSelect = new SelectSql(
                List.of(new SelectColumn(new ColumnRefExpression("management_drafts", "id"), null)),
                "management_drafts",
                List.of(),
                new BinaryOpExpression(new ColumnRefExpression("management_drafts", "id"), BinaryOperator.EQUAL, new VariableRefExpression("p_draft_id")),
                List.of(),
                null,
                List.of(),
                null,
                null,
                new LockingClause(true, false, false, false),
                List.of());

        String sql = new MySqlEmitter().visitExecuteSqlStatement(new ExecuteSqlStatement(lockingSelect));

        assertEquals(
                "SELECT `management_drafts`.`id` FROM `management_drafts` "
                        + "WHERE (`management_drafts`.`id` = p_draft_id) FOR UPDATE;",
                sql);
    }

    @Test
    void emitsEarlyVoidReturnInProcedureAsLeaveOfLabeledBody() {
        // Phase A4 / G2: an early `return;` inside a read-decide-branch procedure cannot emit
        // RETURN; on MySQL (RETURN is function-only). The body is wrapped in a labeled BEGIN...END
        // that the early void return LEAVEs, falling through to the epilogue (time-zone restore).
        Block body = new Block(
                List.of(new DeclareVariable("v_present", new TBooleanType(), true, null)),
                List.of(
                        new SelectIntoStatement("v_present", new SelectSql(
                                List.of(new SelectColumn(new ColumnRefExpression("gate_idempotency", "idempotency_key"), null)),
                                false, "gate_idempotency", null, List.of(),
                                new BinaryOpExpression(new ColumnRefExpression("gate_idempotency", "idempotency_key"), BinaryOperator.EQUAL, new VariableRefExpression("p_key")),
                                List.of(), null, List.of(), null, null, true, null, List.of())),
                        new IfStatement(
                                new VariableRefExpression("v_present"),
                                new Block(List.of(), List.of(new ReturnStatement(null)), List.of()),
                                List.of(),
                                null)),
                List.of());

        String sql = new MySqlEmitter().emitProcedure("test", "guard", SecurityMode.INVOKER, body);

        assertTrue(sql.contains("proc_body: BEGIN"), sql);
        assertTrue(sql.contains("LEAVE proc_body;"), sql);
        assertFalse(sql.contains("RETURN;"), "MySQL procedure void return must be LEAVE, not RETURN; " + sql);
        // The time-zone restore stays in the epilogue, after the labeled body block, so the early
        // LEAVE still restores the caller's session zone.
        int leaveAt = sql.indexOf("LEAVE proc_body;");
        int restoreAt = sql.lastIndexOf("SET time_zone = __titan_saved_time_zone;");
        assertTrue(restoreAt > leaveAt, "time-zone restore must follow the labeled body: " + sql);
    }

    @Test
    void emitsScalarSelectIntoForReadIntoLocal() {
        // Phase A4 / G2: a scalar read bound to a routine local renders SELECT <col> INTO <var>
        // FROM ... on MySQL identically to PostgreSQL — the INTO target between the select list and
        // FROM. No-row leaves the local at its DECLAREd NULL default.
        SelectSql scalarSelect = new SelectSql(
                List.of(new SelectColumn(new ColumnRefExpression("drafts", "version"), null)),
                "drafts",
                List.of(),
                new BinaryOpExpression(new ColumnRefExpression("drafts", "id"), BinaryOperator.EQUAL, new VariableRefExpression("p_id")),
                List.of(),
                null,
                List.of(),
                1,
                null,
                null,
                List.of());

        String sql = new MySqlEmitter().visitSelectIntoStatement(new SelectIntoStatement("v_current", scalarSelect));

        assertEquals(
                "SELECT `drafts`.`version` INTO v_current FROM `drafts` "
                        + "WHERE (`drafts`.`id` = p_id) LIMIT 1;",
                sql);
    }

    @Test
    void emitsSelectExistsIntoForExistenceReadIntoLocal() {
        // Phase A4 / G2: an existence read bound to a boolean local renders
        // SELECT EXISTS (<subquery>) INTO <var>; — always a single boolean row (false when empty).
        SelectSql existsSelect = new SelectSql(
                List.of(new SelectColumn(new ColumnRefExpression("idempotency", "idempotency_key"), null)),
                false,
                "idempotency",
                null,
                List.of(),
                new BinaryOpExpression(new ColumnRefExpression("idempotency", "idempotency_key"), BinaryOperator.EQUAL, new VariableRefExpression("p_key")),
                List.of(),
                null,
                List.of(),
                null,
                null,
                true,
                null,
                List.of());

        String sql = new MySqlEmitter().visitSelectIntoStatement(new SelectIntoStatement("v_present", existsSelect));

        assertEquals(
                "SELECT EXISTS (SELECT `idempotency`.`idempotency_key` FROM `idempotency` "
                        + "WHERE (`idempotency`.`idempotency_key` = p_key)) INTO v_present;",
                sql);
    }

    @Test
    void rejectsReturningOnMySqlUpdateAndDelete() {
        UpdateSql update = new UpdateSql(
                "accounts",
                List.of(new SetClause("active", new LiteralExpression(false, new TBooleanType()))),
                null,
                List.of("id"));
        DeleteSql delete = new DeleteSql("accounts", null, List.of("id"));

        UnsupportedOperationException updateError = assertThrows(
                UnsupportedOperationException.class,
                () -> new MySqlEmitter().visitUpdateSql(update));
        UnsupportedOperationException deleteError = assertThrows(
                UnsupportedOperationException.class,
                () -> new MySqlEmitter().visitDeleteSql(delete));

        assertTrue(updateError.getMessage().contains("TITAN-E001"));
        assertTrue(updateError.getMessage().contains("UPDATE ... RETURNING"));
        assertTrue(updateError.getMessage().contains("MySQL"));
        assertTrue(deleteError.getMessage().contains("TITAN-E001"));
        assertTrue(deleteError.getMessage().contains("DELETE ... RETURNING"));
        assertTrue(deleteError.getMessage().contains("MySQL"));
    }

    @Test
    void escapesQuotesAndBackslashesInMySqlStringLiterals() {
        MySqlEmitter emitter = new MySqlEmitter();

        assertEquals("'it''s'", emitter.visitLiteralExpression(new LiteralExpression("it's", new TTextType())));
        assertEquals("'a\\\\'", emitter.visitLiteralExpression(new LiteralExpression("a\\", new TTextType())));
        assertEquals("'\\\\'''", emitter.visitLiteralExpression(new LiteralExpression("\\'", new TTextType())));
        assertEquals("''''''", emitter.visitLiteralExpression(new LiteralExpression("''", new TTextType())));
    }

    @Test
    void emitsRecordModelAsJsonFactoryAndAccessorFunctions() {
        RecordModelSpec spec = new RecordModelSpec(
                "CustomerSnapshot",
                List.of(
                        new RecordModelSpec.RecordField("id", "long"),
                        new RecordModelSpec.RecordField("email", "String")));

        String sql = new MySqlEmitter().emitRecordModel("app", spec);

        assertTrue(sql.contains("CREATE FUNCTION `app`.`__record_customer_snapshot__new`("));
        assertTrue(sql.contains("RETURNS JSON"));
        assertTrue(sql.contains("SQL SECURITY INVOKER"));
        assertTrue(sql.contains("JSON_OBJECT('id', p_id, 'email', p_email)"));
        assertTrue(sql.contains("CREATE FUNCTION `app`.`__record_customer_snapshot__id`(p_record JSON) RETURNS BIGINT"));
        // CAST has no BIGINT target on MySQL (SIGNED is the integer cast type); RETURNS BIGINT
        // coerces the result (plan 4.4 deployability fix).
        assertTrue(sql.contains("RETURN CAST(JSON_UNQUOTE(JSON_EXTRACT(p_record, '$.id')) AS SIGNED);"));
        assertTrue(sql.contains("CREATE FUNCTION `app`.`__record_customer_snapshot__email`(p_record JSON) RETURNS TEXT"));
        assertTrue(sql.contains("RETURN JSON_UNQUOTE(JSON_EXTRACT(p_record, '$.email'));"));
    }

    @Test
    void supportsConfigurableNamingPrefixesInGeneratedHelpers() {
        RecordModelSpec spec = new RecordModelSpec(
                "CustomerSnapshot",
                List.of(
                        new RecordModelSpec.RecordField("id", "long"),
                        new RecordModelSpec.RecordField("email", "String")));

        MySqlEmitter emitter = new MySqlEmitter(new NamingConventionEngine("arg", "tmp", "const"));
        String recordSql = emitter.emitRecordModel("app", spec);
        String enumSql = emitter.emitEnumLookup("app", new EnumLookupSpec(
                "AccountState",
                List.of(new EnumLookupSpec.EnumField("label", "String")),
                List.of(),
                List.of(new EnumLookupSpec.EnumValue("ACTIVE", List.of("active")))));

        assertTrue(recordSql.contains("JSON_OBJECT('id', arg_id, 'email', arg_email)"));
        assertTrue(recordSql.contains("CREATE FUNCTION `app`.`__record_customer_snapshot__email`(arg_record JSON) RETURNS TEXT"));
        assertTrue(enumSql.contains("CREATE FUNCTION `app`.`__enum_account_state__label`(arg_enum_key VARCHAR(191)) RETURNS TEXT"));
        assertTrue(enumSql.contains("SQL SECURITY INVOKER"));
        assertTrue(enumSql.contains("DECLARE tmp_value TEXT;"));
    }

    @Test
    void emitsMySqlStaticFieldHelperCalls() {
        MySqlEmitter emitter = new MySqlEmitter();

        String getSql = emitter.visitFunctionCallExpression(new FunctionCallExpression(
                "__titan_static_get",
                List.of(new LiteralExpression("Demo#counter", new TTextType())),
                null));
        String setSql = emitter.visitCallStatement(new CallStatement(
                "__titan_static_set",
                List.of(
                        new LiteralExpression("Demo#counter", new TTextType()),
                        new LiteralExpression(1, new TIntType()))));

        assertTrue(getSql.equals("titan_rt_static_get('Demo#counter')"));
        assertTrue(setSql.equals("SET @__titan_static_write = titan_rt_static_set('Demo#counter', CAST(1 AS CHAR));"));
    }

    @Test
    void resetsStaticFieldsOnReturnAndExceptionPaths() {
        Block body = new Block(
                List.of(),
                List.of(
                        new CallStatement("__titan_static_set", List.of(
                                new LiteralExpression("Demo#counter", new TTextType()),
                                new LiteralExpression(1, new TIntType()))),
                        new ReturnStatement(new LiteralExpression(1, new TIntType()))),
                List.of());

        String sql = new MySqlEmitter().emitFunction("app", "with_static", SecurityMode.INVOKER, new TIntType(), body);

        assertTrue(sql.contains("SET @__titan_static_reset = titan_rt_static_reset('Demo#counter');"));
        assertTrue(sql.contains("SET __titan_return_value = 1;"));
        assertTrue(sql.contains("RETURN __titan_return_value;"));
        assertTrue(sql.contains("DECLARE EXIT HANDLER FOR SQLEXCEPTION"));
    }

    @Test
    void labelsNestedLoopsAndBindsBreakContinueToInnermostLabel() {
        // outer while: continue at outer level; inner while: break + continue must bind to
        // the inner label only.
        Block innerBody = new Block(
                List.of(),
                List.of(
                        new IfStatement(
                                new BinaryOpExpression(
                                        new VariableRefExpression("v_inner"),
                                        BinaryOperator.EQUAL,
                                        new LiteralExpression(3, new TIntType())),
                                new Block(List.of(), List.of(new ContinueStatement(null)), List.of()),
                                List.of(),
                                null),
                        new IfStatement(
                                new BinaryOpExpression(
                                        new VariableRefExpression("v_inner"),
                                        BinaryOperator.EQUAL,
                                        new LiteralExpression(5, new TIntType())),
                                new Block(List.of(), List.of(new BreakStatement(null)), List.of()),
                                List.of(),
                                null)),
                List.of());
        WhileStatement inner = new WhileStatement(
                new BinaryOpExpression(
                        new VariableRefExpression("v_inner"),
                        BinaryOperator.LESS_THAN,
                        new LiteralExpression(10, new TIntType())),
                innerBody,
                null);
        Block outerBody = new Block(
                List.of(),
                List.of(
                        inner,
                        new IfStatement(
                                new BinaryOpExpression(
                                        new VariableRefExpression("v_outer"),
                                        BinaryOperator.EQUAL,
                                        new LiteralExpression(2, new TIntType())),
                                new Block(List.of(), List.of(new ContinueStatement(null)), List.of()),
                                List.of(),
                                null),
                        new BreakStatement(null)),
                List.of());
        Block body = new Block(
                List.of(
                        new DeclareVariable("v_outer", new TIntType(), false, null),
                        new DeclareVariable("v_inner", new TIntType(), false, null)),
                List.of(new WhileStatement(
                        new BinaryOpExpression(
                                new VariableRefExpression("v_outer"),
                                BinaryOperator.LESS_THAN,
                                new LiteralExpression(10, new TIntType())),
                        outerBody,
                        null)),
                List.of());

        String sql = new MySqlEmitter().emitProcedure("app", "nested_loops", SecurityMode.INVOKER, body);

        // Deterministic per-routine labels in emission order: outer first, inner second.
        assertTrue(sql.contains("titan_loop_1: WHILE (v_outer < 10) DO"), sql);
        assertTrue(sql.contains("titan_loop_2: WHILE (v_inner < 10) DO"), sql);
        assertTrue(sql.contains("END WHILE titan_loop_2;"), sql);
        assertTrue(sql.contains("END WHILE titan_loop_1;"), sql);
        // Inner break/continue bind to the inner label only.
        assertTrue(sql.contains("ITERATE titan_loop_2;"), sql);
        assertTrue(sql.contains("LEAVE titan_loop_2;"), sql);
        // Outer-level break/continue bind to the outer label.
        assertTrue(sql.contains("ITERATE titan_loop_1;"), sql);
        assertTrue(sql.contains("LEAVE titan_loop_1;"), sql);

        // The labels are deterministic across emissions of the same TIR.
        assertEquals(sql, new MySqlEmitter().emitProcedure("app", "nested_loops", SecurityMode.INVOKER, body));
    }

    @Test
    void emitsForRangeAsIncrementFirstLabeledLoop() {
        Block body = new Block(
                List.of(new DeclareVariable("v_i", new TIntType(), false, null)),
                List.of(new ForRangeStatement(
                        "v_i",
                        new LiteralExpression(1, new TIntType()),
                        new LiteralExpression(5, new TIntType()),
                        new Block(
                                List.of(),
                                List.of(
                                        new IfStatement(
                                                new BinaryOpExpression(
                                                        new VariableRefExpression("v_i"),
                                                        BinaryOperator.EQUAL,
                                                        new LiteralExpression(3, new TIntType())),
                                                new Block(List.of(), List.of(new ContinueStatement(null)), List.of()),
                                                List.of(),
                                                null),
                                        new CallStatement("do_work", List.of(new VariableRefExpression("v_i")))),
                                List.of()),
                        null)),
                List.of());

        String sql = new MySqlEmitter().emitProcedure("app", "range_loop", SecurityMode.INVOKER, body);

        // MySQL has no FOR: the desugar increments and tests the bound at the TOP of a labeled
        // LOOP so that ITERATE (continue) re-runs increment-then-test like Java's for-loop.
        assertTrue(sql.contains("SET v_i = 1 - 1;"), sql);
        assertTrue(sql.contains("titan_loop_1: LOOP"), sql);
        assertTrue(sql.contains("SET v_i = v_i + 1;"), sql);
        assertTrue(sql.contains("IF v_i > 5 THEN LEAVE titan_loop_1; END IF;"), sql);
        assertTrue(sql.contains("ITERATE titan_loop_1;"), sql);
        assertTrue(sql.contains("END LOOP titan_loop_1;"), sql);
        assertFalse(sql.contains("END WHILE"), sql);
    }

    @Test
    void rejectsUnlabeledBreakOutsideOfLoop() {
        Block body = new Block(List.of(), List.of(new BreakStatement(null)), List.of());

        assertThrows(
                io.titan.transpiler.diagnostics.TitanDiagnosticException.class,
                () -> new MySqlEmitter().emitProcedure("app", "broken", SecurityMode.INVOKER, body));
    }

    @Test
    void emitsSignalWithStringLiteralMessageByteIdentically() {
        // F-10 (plan 2.2): string-literal messages keep the exact pre-expression shape.
        String sql = new MySqlEmitter().visitRaiseStatement(
                new RaiseStatement("45000", new LiteralExpression("boom", new TTextType()), List.of()));

        assertEquals("SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'boom';", sql);
    }

    @Test
    void emitsSignalWithDynamicMessageStagedThroughVariable() {
        // F-10 (plan 2.2): SIGNAL ... SET MESSAGE_TEXT only accepts a simple value (literal or
        // variable), so dynamic messages are staged through a variable first — the E-1
        // staged-rethrow pattern.
        String sql = new MySqlEmitter().visitRaiseStatement(new RaiseStatement(
                "45002",
                new FunctionCallExpression("__titan_str_concat", List.of(
                        new LiteralExpression("failed: ", new TTextType()),
                        new VariableRefExpression("v_reason")), null),
                List.of()));

        assertEquals("SET @__titan_raise_message = "
                + "COALESCE(CONCAT(COALESCE(CAST('failed: ' AS CHAR), 'null'), COALESCE(CAST(v_reason AS CHAR), 'null')), 'Java throw');\n"
                + "SIGNAL SQLSTATE '45002' SET MESSAGE_TEXT = @__titan_raise_message;", sql);
        assertFalse(sql.contains("SET MESSAGE_TEXT = COALESCE"),
                "MESSAGE_TEXT must never be followed by an expression (E-1 regression shape)");
    }

    @Test
    void emitsCastExpressionsWithMySqlCastKeywords() {
        MySqlEmitter emitter = new MySqlEmitter();

        // MySQL CAST takes a closed keyword list: integers cast as SIGNED, never INT/BIGINT.
        assertEquals("CAST(v_small AS SIGNED)", emitter.visitCastExpression(
                new CastExpression(new VariableRefExpression("v_small"), new TBigintType())));
        assertEquals("CAST(v_small AS DECIMAL(38,10))", emitter.visitCastExpression(
                new CastExpression(new VariableRefExpression("v_small"), new TNumericType(38, 10))));

        // Fractional-to-integral: Java truncates toward zero, so the CAST composes
        // TRUNCATE(x, 0) (a bare CAST AS SIGNED would round 7.9 to 8 where Java yields 7).
        assertEquals("CAST(TRUNCATE(v_ratio, 0) AS SIGNED)", emitter.visitCastExpression(
                new CastExpression(new VariableRefExpression("v_ratio"), new TBigintType(), true)));
        assertEquals("CAST(TRUNCATE(v_ratio, 0) AS SIGNED)", emitter.visitCastExpression(
                new CastExpression(new VariableRefExpression("v_ratio"), new TIntType(), true)));
    }

    @Test
    void ordersTelemetryVariablesBeforeBodyCursorsAndHandlersAfterThem() {
        // E-12 (plan 3.1): MySQL requires DECLARE order variables -> cursors -> handlers. With
        // observability enabled the telemetry DECLAREs used to follow the body declarations, so
        // a body cursor declaration made CREATE fail with "Variable or condition declaration
        // after cursor or handler declaration".
        Block body = new Block(
                List.of(
                        new DeclareVariable("total", new TIntType(), false, new LiteralExpression(0, new TIntType())),
                        new DeclareCursor("rows", new SelectSql(
                                List.of(new SelectColumn(new ColumnRefExpression(null, "id"), null)),
                                "accounts",
                                List.of(),
                                null,
                                List.of(),
                                null,
                                List.of(),
                                null,
                                null,
                                null,
                                List.of()))),
                List.of(),
                List.of());

        String sql = new MySqlEmitter().emitProcedure("app", "obs_cursor", SecurityMode.INVOKER, body, true, List.of());

        int bodyVariable = sql.indexOf("DECLARE v_total INT");
        int telemetryVariable = sql.indexOf("DECLARE __titan_telemetry_id BIGINT DEFAULT NULL;");
        int savedLastInsertId = sql.indexOf("DECLARE __titan_saved_last_insert_id BIGINT DEFAULT LAST_INSERT_ID();");
        int savedTimeZone = sql.indexOf("DECLARE __titan_saved_time_zone VARCHAR(64) DEFAULT @@session.time_zone;");
        int cursor = sql.indexOf("CURSOR FOR");
        int handler = sql.indexOf("DECLARE EXIT HANDLER FOR SQLEXCEPTION");
        assertTrue(bodyVariable >= 0, sql);
        assertTrue(telemetryVariable > bodyVariable, sql);
        assertTrue(savedLastInsertId > telemetryVariable, sql);
        assertTrue(savedTimeZone > savedLastInsertId, sql);
        assertTrue(cursor > savedTimeZone, sql);
        assertTrue(handler > cursor, sql);
    }

    @Test
    void savesAndRestoresSessionTimeZoneAcrossAllMySqlExitPaths() {
        // E-8 (plan 3.1): the caller's @@session.time_zone is saved into a DECLAREd local
        // (reentrancy-safe under nested generated routines, unlike a session @variable). A
        // routine pins only when its caller is not already UTC, so nested generated functions
        // do not repeatedly mutate shared session state; the saved zone is restored on every
        // exit path only when this routine performed that pin.
        Block procedureBody = new Block(List.of(), List.of(), List.of());
        String procedureSql = new MySqlEmitter().emitProcedure("app", "tz_proc", SecurityMode.INVOKER, procedureBody);

        assertTrue(procedureSql.contains("DECLARE __titan_saved_time_zone VARCHAR(64) DEFAULT @@session.time_zone;"));
        assertTrue(procedureSql.contains("DECLARE __titan_time_zone_pinned BOOLEAN DEFAULT FALSE;"), procedureSql);
        int pinGate = procedureSql.indexOf("IF @@session.time_zone <> '+00:00' THEN");
        int pin = procedureSql.indexOf("SET time_zone = '+00:00';", pinGate);
        int handlerRestore = procedureSql.indexOf("IF __titan_time_zone_pinned THEN SET time_zone = __titan_saved_time_zone; END IF;");
        int resignal = procedureSql.indexOf("RESIGNAL;");
        int tailRestore = procedureSql.lastIndexOf("IF __titan_time_zone_pinned THEN SET time_zone = __titan_saved_time_zone; END IF;");
        assertTrue(pinGate >= 0, procedureSql);
        assertTrue(pin >= 0, procedureSql);
        // Exception path: the EXIT handler restores before re-raising.
        assertTrue(handlerRestore >= 0 && handlerRestore < resignal, procedureSql);
        // Fall-through path: the routine tail restores after the body statements.
        assertTrue(tailRestore > pin, procedureSql);

        Block functionBody = new Block(
                List.of(),
                List.of(new ReturnStatement(new LiteralExpression(5, new TIntType()))),
                List.of());
        String functionSql = new MySqlEmitter().emitFunction(
                "app", "tz_fn", SecurityMode.INVOKER, new TIntType(), functionBody, List.of());

        // RETURN path: the value is staged under UTC, the zone restored, the staged value returned.
        assertTrue(functionSql.contains("DECLARE __titan_return_value INT;"), functionSql);
        int staged = functionSql.indexOf("SET __titan_return_value = 5;");
        int returnRestore = functionSql.indexOf("IF __titan_time_zone_pinned THEN SET time_zone = __titan_saved_time_zone; END IF;", staged);
        int returnStatement = functionSql.indexOf("RETURN __titan_return_value;");
        assertTrue(staged >= 0, functionSql);
        assertTrue(returnRestore > staged, functionSql);
        assertTrue(returnStatement > returnRestore, functionSql);
    }

    @Test
    void triggersDoNotPinOrRestoreSessionTimeZone() {
        // Triggers never emit the UTC pin, so they must not reference the saved-zone local.
        Block body = new Block(
                List.of(),
                List.of(new Assign(
                        new ColumnRefExpression("NEW", "updated_by"),
                        new LiteralExpression("titan", new TTextType()))),
                List.of());

        String sql = new MySqlEmitter().emitTrigger(
                "app",
                "audit_rows",
                SecurityMode.DEFINER,
                new TriggerSpec("audit", "BEFORE", List.of("UPDATE"), "ROW"),
                body);

        assertFalse(sql.contains("time_zone"), sql);
        assertFalse(sql.contains("__titan_saved_time_zone"), sql);
    }

    @Test
    void emitsControlCharacterStringLiteralsWithBackslashEscapes() {
        // B-3 (TG-BLK-006): control characters must never reach the SQL as raw bytes —
        // indentMultiline treats an embedded LF as a line break and "indents" it, silently
        // changing the literal's value. MySQL renders the named backslash escapes inside the
        // literal; characters without a named escape (form feed, vertical tab — MySQL's \f is
        // just 'f') compose through CHAR(n USING utf8mb4).
        MySqlEmitter emitter = new MySqlEmitter();

        // Char literals were already safe via CHAR(n USING utf8mb4); pin that.
        assertEquals("CHAR(10 USING utf8mb4)",
                emitter.visitLiteralExpression(new LiteralExpression('\n', new TTextType())));

        assertEquals("'a\\nb'", emitter.visitLiteralExpression(
                new LiteralExpression("a\nb", new TTextType())));
        assertEquals("'\\r\\n'", emitter.visitLiteralExpression(
                new LiteralExpression("\r\n", new TTextType())));
        assertEquals("'a\\tb''c\\\\d'", emitter.visitLiteralExpression(
                new LiteralExpression("a\tb'c\\d", new TTextType())));
        assertEquals("CONCAT('a\\n', CHAR(11 USING utf8mb4), 'b')", emitter.visitLiteralExpression(
                new LiteralExpression("a\n\u000Bb", new TTextType())));
        assertEquals("CHAR(12 USING utf8mb4)", emitter.visitLiteralExpression(
                new LiteralExpression("\f", new TTextType())));

        // Control-free literals keep the historical rendering (E-2 backslash doubling).
        assertEquals("'it''s a\\\\b'", emitter.visitLiteralExpression(
                new LiteralExpression("it's a\\b", new TTextType())));
    }

    @Test
    void emitsGeneratedKeyReadAsInsertThenSetLastInsertId() {
        // JDBC I-7 generated-key recovery (§6.3 / I-R8): MySQL has no RETURNING, so the form is the
        // static INSERT immediately followed by 'SET <keyLocal> = LAST_INSERT_ID();' — exactly what the
        // MySQL JDBC driver returns for getGeneratedKeys(). The SET-immediately-after-INSERT invariant
        // is load-bearing: LAST_INSERT_ID() is the session's last AUTO_INCREMENT, so no statement may
        // run between the INSERT and the SET. The ? bind is spliced as the bound routine-local
        // identifier (p_customer_id) — a typed parameter reference, never a literal.
        GeneratedKeyReadStatement node = new GeneratedKeyReadStatement(
                new RawSql("INSERT INTO invoices (customer_id) VALUES (?)", List.of("p_customer_id"), "MYSQL"),
                "id",
                "__titan_genkey1");

        String emitted = new MySqlEmitter().visitGeneratedKeyReadStatement(node);

        assertEquals(
                "INSERT INTO invoices (customer_id) VALUES (p_customer_id); "
                        + "SET __titan_genkey1 = LAST_INSERT_ID();",
                emitted);
        // Pin the SET-immediately-after-INSERT invariant explicitly: the only ';' separating two
        // statements sits between the INSERT and the SET (nothing emitted in between).
        assertEquals(
                "INSERT INTO invoices (customer_id) VALUES (p_customer_id)",
                emitted.substring(0, emitted.indexOf("; ")));
        assertTrue(emitted.substring(emitted.indexOf("; ") + 2).startsWith("SET __titan_genkey1 = LAST_INSERT_ID();"),
                "SET <keyLocal> = LAST_INSERT_ID() must immediately follow the INSERT, with no statement between");
    }
}
