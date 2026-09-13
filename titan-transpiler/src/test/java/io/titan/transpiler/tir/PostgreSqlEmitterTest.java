package io.titan.transpiler.tir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.titan.transpiler.NamingConventionEngine;
import java.util.List;
import org.junit.jupiter.api.Test;

class PostgreSqlEmitterTest {

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

        String sql = new PostgreSqlEmitter().emitProcedure("app", "process_accounts", SecurityMode.INVOKER, body);

        assertTrue(sql.contains("CREATE OR REPLACE PROCEDURE \"app\".\"process_accounts\"()"));
        assertTrue(sql.contains("LANGUAGE plpgsql"));
        assertTrue(sql.contains("SECURITY INVOKER"));
        assertTrue(sql.contains("v_total INTEGER := 0;"));
        assertTrue(sql.contains("IF (v_total > 0) THEN"));
        assertTrue(sql.contains("CALL \"do_work\"();"));
        assertTrue(sql.contains("WHILE (v_total < 10) LOOP"));
        assertTrue(sql.contains("-- titan:source:Sample.java:42"));
        assertTrue(sql.contains("IF v_total IS NULL THEN RAISE EXCEPTION 'NullPointerException at Sample.java:42'; END IF;"));
        assertTrue(sql.contains("RAISE NOTICE '%', 'tick';"));
        assertTrue(sql.contains("RETURN;"));
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
                () -> new PostgreSqlEmitter().visitFunctionCallExpression(marker));
        assertTrue(exception.getMessage().contains("NullAnalysisPass"));
    }

    @Test
    void emitsEmulationDivisionAndModuloMarkersAsNativeOperators() {
        // Plan 2.4 (E-7): native PostgreSQL integer '/' and '%' already match Java exactly
        // (truncation toward zero, dividend-sign remainder, 22012 on zero divisor), so the
        // EmulationInsertionPass markers render as plain operators — byte-identical to the
        // pre-pass output. titan_runtime.java_mod deliberately stays unwired here.
        PostgreSqlEmitter emitter = new PostgreSqlEmitter();
        List<ExpressionNode> args = List.of(new VariableRefExpression("a"), new VariableRefExpression("b"));

        assertEquals("(a / b)", emitter.visitFunctionCallExpression(
                new FunctionCallExpression(EmulationInsertionPass.INT_DIV_MARKER, args, null)));
        assertEquals("(a % b)", emitter.visitFunctionCallExpression(
                new FunctionCallExpression(EmulationInsertionPass.INT_MOD_MARKER, args, null)));
    }

    @Test
    void emitsWraparoundMarkersAsRuntimeCalls() {
        // Plan 2.4 (E-11): strict-wraparound markers call the deployed titan_runtime helpers.
        PostgreSqlEmitter emitter = new PostgreSqlEmitter();
        List<ExpressionNode> args = List.of(new VariableRefExpression("a"), new VariableRefExpression("b"));

        assertEquals("titan_runtime.java_int_add(a, b)", emitter.visitFunctionCallExpression(
                new FunctionCallExpression(EmulationInsertionPass.INT_ADD_MARKER, args, null)));
        assertEquals("titan_runtime.java_int_sub(a, b)", emitter.visitFunctionCallExpression(
                new FunctionCallExpression(EmulationInsertionPass.INT_SUB_MARKER, args, null)));
        assertEquals("titan_runtime.java_int_mul(a, b)", emitter.visitFunctionCallExpression(
                new FunctionCallExpression(EmulationInsertionPass.INT_MUL_MARKER, args, null)));
    }

    @Test
    void emitsObservabilityPayloadWithParameterTypes() {
        Block body = new Block(
                List.of(new DeclareVariable("p_account_id", new TIntType(), false, null)),
                List.of(new ReturnStatement(null)),
                List.of());

        String sql = new PostgreSqlEmitter().emitProcedure(
                "app",
                "telemetry_proc",
                SecurityMode.INVOKER,
                body,
                true,
                List.of("accounts.email"));

        assertTrue(sql.contains("\"parameterTypes\":{\"p_account_id\":\"INTEGER\"}"));
        assertTrue(sql.contains("\"sensitiveColumns\":[\"accounts.email\"]"));
    }

    @Test
    void emitsRoutineDeclarationsBeforePostgresBegin() {
        Block body = new Block(
                List.of(new DeclareVariable("v_total", new TIntType(), false, new LiteralExpression(0, new TIntType()))),
                List.of(new ReturnStatement(null)),
                List.of());

        String sql = new PostgreSqlEmitter().emitProcedure("app", "declared_proc", SecurityMode.INVOKER, body, true);

        int declareSection = sql.indexOf("DECLARE\n");
        int begin = sql.indexOf("BEGIN\n");
        assertTrue(declareSection > 0);
        assertTrue(begin > declareSection);
        assertTrue(sql.contains("    __titan_telemetry_id BIGINT;"));
        assertTrue(sql.contains("    v_total INTEGER := 0;"));
        assertFalse(sql.contains("BEGIN\n    DECLARE"));
    }

    @Test
    void emitsJavaLocalInitializerAtStatementPositionOnly() {
        ExpressionNode initializer = new FunctionCallExpression(
                "derive_value",
                List.of(new VariableRefExpression("v_position")),
                null);
        Block body = new Block(
                List.of(new DeclareVariable("value", new TIntType(), false, initializer)),
                List.of(
                        new Assign(new VariableRefExpression("v_position"), new LiteralExpression(7, new TIntType())),
                        new Assign(new VariableRefExpression("value"), initializer),
                        new ReturnStatement(new VariableRefExpression("value"))),
                List.of());

        String sql = new PostgreSqlEmitter().emitFunction("app", "ordered_init", SecurityMode.INVOKER, new TIntType(), body);

        assertTrue(sql.contains("v_value INTEGER;"));
        assertFalse(sql.contains("v_value INTEGER := derive_value(v_position);"));
        assertTrue(sql.indexOf("v_position := 7;") < sql.indexOf("v_value := derive_value(v_position);"));
    }

    @Test
    void emitsObservabilitySuccessBeforeFunctionReturn() {
        Block body = new Block(
                List.of(),
                List.of(new ReturnStatement(new LiteralExpression(7, new TIntType()))),
                List.of());

        String sql = new PostgreSqlEmitter().emitFunction(
                "app",
                "telemetry_fn",
                SecurityMode.INVOKER,
                new TIntType(),
                body,
                true);

        int success = sql.indexOf("status = 'success'");
        int returnStatement = sql.indexOf("RETURN 7;");
        assertTrue(success >= 0);
        assertTrue(returnStatement > success);
    }

    @Test
    void emitsRoutineParametersFromPrefixedDeclarations() {
        Block body = new Block(
                List.of(
                        new DeclareVariable("p_account_id", new TIntType(), false, null),
                        new DeclareVariable("v_total", new TIntType(), false, new LiteralExpression(0, new TIntType()))),
                List.of(new ReturnStatement(null)),
                List.of());

        String sql = new PostgreSqlEmitter().emitProcedure("app", "proc_with_params", SecurityMode.INVOKER, body);

        assertTrue(sql.contains("CREATE OR REPLACE PROCEDURE \"app\".\"proc_with_params\"(p_account_id INTEGER)"));
        assertFalse(sql.contains("DECLARE p_account_id"));
        assertTrue(sql.contains("v_total INTEGER := 0;"));
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

        String sql = new PostgreSqlEmitter().emitFunction(
                "app",
                "sum_users",
                SecurityMode.INVOKER,
                new TIntType(),
                body,
                List.of(
                        new RoutineParameter("userId", "p_user_id", new TIntType()),
                        new RoutineParameter("user_id", "p_user_id", new TIntType())));

        assertTrue(sql.contains("CREATE OR REPLACE FUNCTION \"app\".\"sum_users\"(p_user_id INTEGER, p_user_id_2 INTEGER)"));
        assertTrue(sql.contains("RETURN (p_user_id + p_user_id_2);"));
    }

    @Test
    void renamesLocalVariablesThatCollideWithPlpgsqlReturnForms() {
        Block body = new Block(
                List.of(
                        new DeclareVariable("next", new TIntType(), false, new LiteralExpression(1, new TIntType())),
                        new DeclareVariable("end", new TIntType(), false, new LiteralExpression(2, new TIntType())),
                        new DeclareVariable("trailing", new TIntType(), false, new LiteralExpression(3, new TIntType()))),
                List.of(new ReturnStatement(new BinaryOpExpression(
                        new BinaryOpExpression(
                                new VariableRefExpression("next"),
                                BinaryOperator.ADD,
                                new VariableRefExpression("end")),
                        BinaryOperator.ADD,
                        new VariableRefExpression("trailing")))),
                List.of());

        String sql = new PostgreSqlEmitter().emitFunction("app", "return_next_value", SecurityMode.INVOKER, new TIntType(), body);

        assertTrue(sql.contains("v_next INTEGER := 1;"));
        assertTrue(sql.contains("v_end INTEGER := 2;"));
        assertTrue(sql.contains("v_trailing INTEGER := 3;"));
        assertTrue(sql.contains("RETURN ((v_next + v_end) + v_trailing);"));
        assertFalse(sql.contains("RETURN next;"));
        assertFalse(sql.contains(" end INTEGER"));
        assertFalse(sql.contains(" trailing INTEGER"));
    }

    @Test
    void emitsStatementBlocksWithDeclarationsAsNestedPlpgsqlBlocks() {
        Block body = new Block(
                List.of(),
                List.of(new Block(
                        List.of(new DeclareVariable("item", new TIntType(), false, new LiteralExpression(1, new TIntType()))),
                        List.of(new CallStatement("consume", List.of(new VariableRefExpression("item")))),
                        List.of())),
                List.of());

        String sql = new PostgreSqlEmitter().emitProcedure("app", "nested_block", SecurityMode.INVOKER, body);

        assertTrue(sql.contains("DECLARE\n        v_item INTEGER := 1;\n    BEGIN\n        CALL \"consume\"(v_item);\n    END;"));
        assertFalse(sql.contains("BEGIN\n    DECLARE v_item"));
    }

    @Test
    void preservesInternalTelemetryIdentifiersByRenamingConflictingLocals() {
        Block body = new Block(
                List.of(new DeclareVariable("__titan_started_at", new TIntType(), false, new LiteralExpression(1, new TIntType()))),
                List.of(new ReturnStatement(null)),
                List.of());

        String sql = new PostgreSqlEmitter().emitProcedure("app", "telemetry_guard", SecurityMode.INVOKER, body, true);

        assertTrue(sql.contains("__titan_started_at_2 INTEGER := 1;"));
        assertTrue(sql.contains("__titan_started_at TIMESTAMPTZ := clock_timestamp();"));
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
                        new ReturnStatement(new VariableRefExpression("v_total"))
                ),
                List.of());

        String sql = new PostgreSqlEmitter().emitFunction("app", "count_active", SecurityMode.INVOKER, new TIntType(), body);

        assertTrue(sql.contains("CREATE OR REPLACE FUNCTION \"app\".\"count_active\"()"));
        assertTrue(sql.contains("RETURNS INTEGER"));
        // G1 (spike B1): a SELECT statement whose result is discarded (an ExecuteSqlStatement
        // wrapping a SelectSql) must render as PERFORM in PL/pgSQL — a bare SELECT raises
        // "query has no destination for result data" at execute. The leading SELECT keyword is
        // dropped; the rest of the query is preserved verbatim.
        assertTrue(sql.contains("PERFORM v_total AS \"total\" FROM \"accounts\" LIMIT 1;"));
        assertFalse(sql.contains("SELECT v_total AS \"total\" FROM \"accounts\" LIMIT 1;"));
        assertTrue(sql.contains("RETURN v_total;"));
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
                "SELECT EXISTS (SELECT \"accounts\".\"id\" FROM \"accounts\" WHERE (\"accounts\".\"active\" = TRUE))",
                new PostgreSqlEmitter().visitSelectSql(selectSql));
    }

    @Test
    void emitsPostgresDmlWithReturningAndConflictClauses() {
        InsertSql insert = new InsertSql(
                "accounts",
                List.of("email", "active"),
                List.of(new LiteralExpression("person@example.com", new TTextType()), new LiteralExpression(true, new TBooleanType())),
                null,
                new ConflictClause(
                        List.of("email"),
                        List.of(new SetClause("active", new LiteralExpression(true, new TBooleanType())))),
                List.of("id"));

        UpdateSql update = new UpdateSql(
                "accounts",
                List.of(new SetClause("active", new LiteralExpression(false, new TBooleanType()))),
                new BinaryOpExpression(new ColumnRefExpression("accounts", "id"), BinaryOperator.EQUAL, new LiteralExpression(7, new TIntType())),
                List.of("id", "active"));

        DeleteSql delete = new DeleteSql(
                "accounts",
                new BinaryOpExpression(new ColumnRefExpression("accounts", "active"), BinaryOperator.EQUAL, new LiteralExpression(false, new TBooleanType())),
                List.of("id"));

        PostgreSqlEmitter emitter = new PostgreSqlEmitter();

        assertTrue(emitter.visitInsertSql(insert)
                .contains("INSERT INTO \"accounts\" (\"email\", \"active\") VALUES ('person@example.com', TRUE) ON CONFLICT (\"email\") DO UPDATE SET \"active\" = TRUE RETURNING \"id\""));
        assertTrue(emitter.visitUpdateSql(update)
                .contains("UPDATE \"accounts\" SET \"active\" = FALSE WHERE (\"accounts\".\"id\" = 7) RETURNING \"id\", \"active\""));
        assertTrue(emitter.visitDeleteSql(delete)
                .contains("DELETE FROM \"accounts\" WHERE (\"accounts\".\"active\" = FALSE) RETURNING \"id\""));
    }

    @Test
    void emitsExpressionValuedSetAsServerSideArithmeticInUpdateAndConflict() {
        // G3 (spike B3): an expression-valued SET right-hand side renders as a quoted,
        // table-qualified column self-reference plus arithmetic (version = "drafts"."version" + 1),
        // valid in both plain UPDATE and ON CONFLICT DO UPDATE on PostgreSQL.
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

        PostgreSqlEmitter emitter = new PostgreSqlEmitter();

        assertEquals(
                "UPDATE \"drafts\" SET \"version\" = (\"drafts\".\"version\" + 1) WHERE (\"drafts\".\"id\" = 7)",
                emitter.visitUpdateSql(update));
        assertTrue(emitter.visitInsertSql(upsert)
                .contains("ON CONFLICT (\"id\") DO UPDATE SET \"version\" = (\"drafts\".\"version\" + 1)"),
                emitter.visitInsertSql(upsert));
    }

    @Test
    void rendersDiscardedForUpdateSelectAsPerform() {
        // G1 (spike B1): the lock-bearing discarded select shape from the dogfood spike —
        // select(ID).from(t).where(ID = p).forUpdate() terminated by fetch() as a statement. On
        // PG a bare SELECT here raises "query has no destination for result data" at execute, so
        // the emitter must issue PERFORM (the leading SELECT keyword dropped, the rest verbatim).
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

        String sql = new PostgreSqlEmitter().visitExecuteSqlStatement(new ExecuteSqlStatement(lockingSelect));

        assertEquals(
                "PERFORM \"management_drafts\".\"id\" FROM \"management_drafts\" "
                        + "WHERE (\"management_drafts\".\"id\" = p_draft_id) FOR UPDATE;",
                sql);
    }

    @Test
    void emitsScalarSelectIntoForReadIntoLocal() {
        // Phase A4 / G2: a scalar read bound to a routine local renders SELECT <col> INTO <var>
        // FROM ... — the INTO target sits between the select list and FROM. No PERFORM: the result
        // has a destination (the local), so plpgsql accepts it. No-row leaves the local NULL.
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

        String sql = new PostgreSqlEmitter().visitSelectIntoStatement(new SelectIntoStatement("v_current", scalarSelect));

        assertEquals(
                "SELECT \"drafts\".\"version\" INTO v_current FROM \"drafts\" "
                        + "WHERE (\"drafts\".\"id\" = p_id) LIMIT 1;",
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

        String sql = new PostgreSqlEmitter().visitSelectIntoStatement(new SelectIntoStatement("v_present", existsSelect));

        assertEquals(
                "SELECT EXISTS (SELECT \"idempotency\".\"idempotency_key\" FROM \"idempotency\" "
                        + "WHERE (\"idempotency\".\"idempotency_key\" = p_key)) INTO v_present;",
                sql);
    }

    @Test
    void wrapsDiscardedCteSelectAsPerformDerivedTable() {
        // A discarded query that does not begin with SELECT (a leading WITH CTE) cannot have its
        // keyword swapped, so PERFORM wraps it as a derived table — still discards the result.
        SelectSql cteSelect = new SelectSql(
                List.of(new SelectColumn(new ColumnRefExpression("c", "id"), null)),
                false,
                "c",
                null,
                List.of(),
                null,
                List.of(),
                null,
                List.of(),
                null,
                null,
                false,
                null,
                List.of(new CteSpec("c", new SelectSql(
                        List.of(new SelectColumn(new ColumnRefExpression("accounts", "id"), null)),
                        "accounts", List.of(), null, List.of(), null, List.of(), null, null, null, List.of()),
                        false)));

        String sql = new PostgreSqlEmitter().visitExecuteSqlStatement(new ExecuteSqlStatement(cteSelect));

        assertTrue(sql.startsWith("PERFORM * FROM (WITH "), sql);
        assertTrue(sql.endsWith(") AS __titan_discarded;"), sql);
    }

    @Test
    void doesNotApplyPerformToDiscardedInsertOrRawStatements() {
        // PERFORM is only for row-returning queries; an INSERT (even with RETURNING) and a RawSql
        // EXECUTE stay plain statements.
        InsertSql insert = new InsertSql(
                "accounts",
                List.of("email"),
                List.of(new LiteralExpression("a@b.com", new TTextType())),
                null,
                null,
                List.of());
        PostgreSqlEmitter emitter = new PostgreSqlEmitter();

        String insertSql = emitter.visitExecuteSqlStatement(new ExecuteSqlStatement(insert));
        assertTrue(insertSql.startsWith("INSERT INTO \"accounts\""), insertSql);
        assertFalse(insertSql.contains("PERFORM"));
    }

    @Test
    void castsJsonColumnSetAndInsertValuesToJsonb() {
        // G4 (spike B4): a value targeting a SQLType.JSON column is lowered to a CAST to TJsonType,
        // which the PG emitter renders as CAST(... AS JSONB) so a jsonb column accepts the text
        // parameter (it otherwise rejects it: "column is of type jsonb but expression is of type text").
        InsertSql insert = new InsertSql(
                "management_drafts",
                List.of("document"),
                List.of(new CastExpression(new VariableRefExpression("p_document"), new TJsonType())),
                null,
                new ConflictClause(
                        List.of("id"),
                        List.of(new SetClause("document", new CastExpression(new VariableRefExpression("p_document"), new TJsonType())))),
                List.of());

        String sql = new PostgreSqlEmitter().visitInsertSql(insert);

        assertTrue(sql.contains("VALUES (CAST(p_document AS JSONB))"), sql);
        assertTrue(sql.contains("DO UPDATE SET \"document\" = CAST(p_document AS JSONB)"), sql);
    }

    @Test
    void emitsLateralJoinForPostgresSelectSql() {
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

        String sql = new PostgreSqlEmitter().visitSelectSql(selectSql);

        assertTrue(sql.contains("FROM \"accounts\" JOIN LATERAL (SELECT \"plans\".\"name\" FROM \"plans\" LIMIT 1) AS \"plan_lateral\""));
    }

    @Test
    void emitsExistsExpressionsForPostgres() {
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

        PostgreSqlEmitter emitter = new PostgreSqlEmitter();

        assertEquals(
                "EXISTS (SELECT \"accounts\".\"id\" FROM \"accounts\" WHERE (\"accounts\".\"plan_code\" = 'enterprise') LIMIT 1)",
                emitter.visitExistsExpression(new ExistsExpression(subquery, false)));
        assertEquals(
                "NOT (EXISTS (SELECT \"accounts\".\"id\" FROM \"accounts\" WHERE (\"accounts\".\"plan_code\" = 'enterprise') LIMIT 1))",
                emitter.visitExistsExpression(new ExistsExpression(subquery, true)));
    }

    @Test
    void escapesWindowFunctionStringArgumentsForPostgres() {
        WindowFunctionExpression lag = new WindowFunctionExpression(
                "LAG",
                List.of(
                        new ColumnRefExpression("accounts", "email"),
                        new LiteralExpression(1, new TIntType()),
                        new LiteralExpression("o'brien@example.com", new TTextType())),
                new WindowSpec(
                        List.of(new ColumnRefExpression("accounts", "plan_id")),
                        List.of(new OrderBySpec(new ColumnRefExpression("accounts", "id"), SortDirection.ASC)),
                        new WindowFrame(
                                WindowFrameUnit.ROWS,
                                new WindowFrameBound(WindowFrameBoundKind.UNBOUNDED_PRECEDING, null),
                                new WindowFrameBound(WindowFrameBoundKind.CURRENT_ROW, null))));

        assertEquals(
                "LAG(\"accounts\".\"email\", 1, 'o''brien@example.com') OVER (PARTITION BY \"accounts\".\"plan_id\""
                        + " ORDER BY \"accounts\".\"id\" ASC ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW)",
                new PostgreSqlEmitter().visitWindowFunctionExpression(lag));
    }

    @Test
    void emitsGroupingSetSpecsForPostgres() {
        PostgreSqlEmitter emitter = new PostgreSqlEmitter();
        ColumnRefExpression tenant = new ColumnRefExpression("accounts", "tenant_id");
        ColumnRefExpression status = new ColumnRefExpression("accounts", "status");

        assertEquals(
                "GROUPING SETS ((\"accounts\".\"tenant_id\", \"accounts\".\"status\"), (\"accounts\".\"tenant_id\"), ())",
                emitter.visitGroupingSetSpec(new GroupingSetSpec(
                        GroupingSetKind.GROUPING_SETS,
                        List.of(List.of(tenant, status), List.of(tenant), List.of()))));
        assertEquals(
                "ROLLUP(\"accounts\".\"tenant_id\", \"accounts\".\"status\")",
                emitter.visitGroupingSetSpec(new GroupingSetSpec(GroupingSetKind.ROLLUP, List.of(List.of(tenant, status)))));
        assertEquals(
                "CUBE(\"accounts\".\"status\")",
                emitter.visitGroupingSetSpec(new GroupingSetSpec(GroupingSetKind.CUBE, List.of(List.of(status)))));
        assertEquals(
                "(\"accounts\".\"tenant_id\", \"accounts\".\"status\")",
                emitter.visitGroupingSetSpec(new GroupingSetSpec(GroupingSetKind.SET, List.of(List.of(tenant, status)))));
    }

    @Test
    void emitsFullOuterJoinForPostgresSelectSql() {
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

        String sql = new PostgreSqlEmitter().visitSelectSql(selectSql);

        assertTrue(sql.contains("FROM \"accounts\" FULL OUTER JOIN \"users\" ON (\"accounts\".\"id\" = \"users\".\"account_id\")"));
    }

    @Test
    void rejectsDeclareHandlerForPostgresEmission() {
        UnsupportedOperationException error = assertThrows(
                UnsupportedOperationException.class,
                () -> new PostgreSqlEmitter().visitDeclareHandler(
                        new DeclareHandler(List.of("SQLEXCEPTION"), HandlerAction.CONTINUE, null)));

        assertTrue(error.getMessage().contains("TITAN-E001"));
        assertTrue(error.getMessage().contains("DECLARE ... HANDLER"));
        assertTrue(error.getMessage().contains("PostgreSQL"));
    }

    @Test
    void escapesQuotesWithoutDoublingBackslashesInPostgresStringLiterals() {
        PostgreSqlEmitter emitter = new PostgreSqlEmitter();

        assertEquals("'it''s'", emitter.visitLiteralExpression(new LiteralExpression("it's", new TTextType())));
        assertEquals("'a\\'", emitter.visitLiteralExpression(new LiteralExpression("a\\", new TTextType())));
        assertEquals("'\\'''", emitter.visitLiteralExpression(new LiteralExpression("\\'", new TTextType())));
        assertEquals("''''''", emitter.visitLiteralExpression(new LiteralExpression("''", new TTextType())));
    }

    @Test
    void emitsRawSqlWithPostgresExecuteUsingBindings() {
        RawSql rawSql = new RawSql(
                "SELECT * FROM accounts WHERE id = :accountId AND status = :status",
                List.of("accountId", "status"),
                "POSTGRESQL");

        String emitted = new PostgreSqlEmitter().visitRawSql(rawSql);

        assertTrue(emitted.contains("EXECUTE 'SELECT * FROM accounts WHERE id = $1 AND status = $2' USING accountId, status"));
    }

    @Test
    void emitsRawReadIntoAsDynamicExecuteIntoUsing() {
        // JDBC I-4 over unparsed text (WS-C Phase 2 §4): native PostgreSQL single-row read. The
        // multi-target INTO sits between EXECUTE '<sql>' and USING; ? placeholders become $n bound
        // by USING order.
        RawReadIntoStatement node = new RawReadIntoStatement(
                List.of("v_balance", "v_tier"),
                new RawSql("SELECT balance, tier FROM accounts WHERE id = ?", List.of("p_account_id"), "POSTGRESQL"));

        String emitted = new PostgreSqlEmitter().visitRawReadIntoStatement(node);

        assertEquals(
                "EXECUTE 'SELECT balance, tier FROM accounts WHERE id = $1' INTO v_balance, v_tier USING p_account_id;",
                emitted);
    }

    @Test
    void emitsRawReadIntoWithoutUsingWhenNoBinds() {
        RawReadIntoStatement node = new RawReadIntoStatement(
                List.of("v_now"),
                new RawSql("SELECT now() FROM dual", List.of(), "POSTGRESQL"));

        String emitted = new PostgreSqlEmitter().visitRawReadIntoStatement(node);

        assertEquals("EXECUTE 'SELECT now() FROM dual' INTO v_now;", emitted);
    }

    @Test
    void emitsRawReadIntoGuardAsRowCountBasedNotFirstColumnNullProxy() {
        // JDBC I-4 §6.1 early-return guard: the no-row check is ROW_COUNT-based, NOT a "first INTO target
        // IS NULL" test — an existing row with a NULL first column (e.g. a nullable note) must not falsely
        // raise (WS-C Phase 2b audit). It is also NOT FOUND-based: a dynamic EXECUTE ... INTO does NOT set
        // FOUND on PostgreSQL (verified live on PG 16), so an IF NOT FOUND guard would raise on EVERY call
        // (WS-C Phase 2c deploy gate). GET DIAGNOSTICS ... = ROW_COUNT is the reliable signal; the
        // row-count local lives in a self-contained DECLARE … BEGIN … END sub-block.
        RawReadIntoStatement node = new RawReadIntoStatement(
                List.of("v_note"),
                new RawSql("SELECT optional_note FROM accounts WHERE id = ?", List.of("p_id"), "POSTGRESQL"),
                new RaiseStatement(null, new LiteralExpression("account not found", new TTextType()), List.of()));

        String emitted = new PostgreSqlEmitter().visitRawReadIntoStatement(node);

        assertEquals(
                "DECLARE\n"
                        + "    titan_row_count INTEGER;\n"
                        + "BEGIN\n"
                        + "    EXECUTE 'SELECT optional_note FROM accounts WHERE id = $1' INTO v_note USING p_id;\n"
                        + "    GET DIAGNOSTICS titan_row_count = ROW_COUNT;\n"
                        + "    IF titan_row_count = 0 THEN\n"
                        + "        RAISE EXCEPTION USING MESSAGE = 'account not found';\n"
                        + "    END IF;\n"
                        + "END;",
                emitted);
        // The guard is ROW_COUNT-based, never a value test of the (possibly NULL) first column, and never
        // the always-false FOUND flag.
        assertFalse(emitted.contains("v_note IS NULL"), "guard must not test the first column value");
        assertFalse(emitted.contains("NOT FOUND"), "guard must not use FOUND (unset after dynamic EXECUTE ... INTO)");
    }

    @Test
    void rewritesPlaceholderAfterDoubleQuotedIdentifierWithDoubledQuote() {
        // The shared ?-rewrite skips ? inside a "" -doubled quoted identifier and rewrites the real
        // trailing bind to $1. PostgreSQL must NOT apply backslash-escapes here (a backslash is an
        // ordinary character in a quoted identifier), so a \" does not extend the identifier — this
        // guards the shared rewritePositionalPlaceholders against the MySQL-only backslash gating.
        RawReadIntoStatement node = new RawReadIntoStatement(
                List.of("v_x"),
                new RawSql("SELECT \"od\"\"d?col\" FROM t WHERE id = ?", List.of("p_id"), "POSTGRESQL"));

        String emitted = new PostgreSqlEmitter().visitRawReadIntoStatement(node);

        // The ? inside the quoted identifier stays literal; only the trailing bind becomes $1.
        assertEquals(
                "EXECUTE 'SELECT \"od\"\"d?col\" FROM t WHERE id = $1' INTO v_x USING p_id;",
                emitted);
    }

    @Test
    void emitsRawCursorAsRefcursorOpenForExecuteWithBody() {
        // JDBC I-5 over unparsed text (WS-C Phase 2 §4): an explicit dynamic refcursor opened over
        // the source SQL, FETCH INTO scalars, with the body run once per row (the existing PG cursor
        // test passes an empty body — this exercises the body-bearing path).
        RawCursorStatement node = new RawCursorStatement(
                List.of("v_amount"),
                new RawSql("SELECT amount FROM invoices WHERE customer_id = ?", List.of("p_customer_id"), "POSTGRESQL"),
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

        String emitted = new PostgreSqlEmitter().visitRawCursorStatement(node);

        assertEquals(
                "DECLARE titan_cur refcursor;\n"
                        + "BEGIN\n"
                        + "    OPEN titan_cur FOR EXECUTE 'SELECT amount FROM invoices WHERE customer_id = $1' USING p_customer_id;\n"
                        + "    LOOP\n"
                        + "        FETCH titan_cur INTO v_amount;\n"
                        + "        EXIT WHEN NOT FOUND;\n"
                        + "        v_total := (v_total + v_amount);\n"
                        + "    END LOOP;\n"
                        + "    CLOSE titan_cur;\n"
                        + "END;",
                emitted);
    }

    /**
     * WS-C Phase 3 Rung 2 deploy fix: when the per-row FETCH-target locals are attached to the cursor
     * <b>body</b>'s declarations (exactly how {@code JdbcStatementLowerer} builds an I-5 / fused cursor —
     * {@code __titan_row_*} added to {@code cursorBody}'s declarations), they MUST be hoisted to the top
     * {@code DECLARE} beside the refcursor, NOT left inside the loop-body sub-block. Left in the body
     * they are out of scope at {@code FETCH … INTO}, and PostgreSQL rejects CREATE with
     * "__titan_row_… is not a known variable". This pins the hoist: the FETCH target is DECLAREd up top
     * and does NOT reappear as a nested {@code DECLARE} in the body.
     */
    @Test
    void hoistsBodyDeclaredFetchTargetsIntoTopDeclareSoFetchIsInScope() {
        RawCursorStatement node = new RawCursorStatement(
                List.of("__titan_row_amount_0"),
                new RawSql(
                        "SELECT amount FROM orders WHERE customer_id IN (SELECT id FROM customers WHERE tier = ?)",
                        List.of("p_tier"), "POSTGRESQL"),
                new Block(
                        // The FETCH-target local lives in the BODY declarations (the lowerer's shape).
                        List.of(new DeclareVariable("__titan_row_amount_0", new TNumericType(38, 10), true, null)),
                        List.of(new Assign(
                                new VariableRefExpression("v_total"),
                                new BinaryOpExpression(
                                        new VariableRefExpression("v_total"),
                                        BinaryOperator.ADD,
                                        new VariableRefExpression("__titan_row_amount_0")))),
                        List.of()),
                null);

        String emitted = new PostgreSqlEmitter().visitRawCursorStatement(node);

        // The FETCH target is hoisted to the top DECLARE (in scope at the FETCH)...
        assertTrue(emitted.startsWith(
                        "DECLARE titan_cur refcursor;\n"
                                + "    __titan_row_amount_0 NUMERIC(38,10);\n"
                                + "BEGIN\n"),
                "FETCH targets in the body must be hoisted to the top DECLARE; was:\n" + emitted);
        assertTrue(emitted.contains("FETCH titan_cur INTO __titan_row_amount_0;"),
                "the FETCH must target the hoisted local; was:\n" + emitted);
        // ...and it must NOT be re-DECLAREd inside the loop body (no nested DECLARE block for it).
        assertFalse(emitted.contains("DECLARE\n    __titan_row_amount_0"),
                "the FETCH target must not be double-declared inside the loop body; was:\n" + emitted);
        // The body statement still runs once per row, referencing the hoisted local.
        assertTrue(emitted.contains("v_total := (v_total + __titan_row_amount_0);"),
                "the per-row body must reference the hoisted FETCH target; was:\n" + emitted);
    }

    @Test
    void emitsTransactionControlAsCommitAndRollback() {
        assertEquals("COMMIT;",
                new PostgreSqlEmitter().visitTransactionControlStatement(
                        new TransactionControlStatement(TransactionAction.COMMIT)));
        assertEquals("ROLLBACK;",
                new PostgreSqlEmitter().visitTransactionControlStatement(
                        new TransactionControlStatement(TransactionAction.ROLLBACK)));
    }

    @Test
    void emitsTryFinallyWithSavedExceptionReraise() {
        Block body = new Block(
                List.of(),
                List.of(new TryCatchFinallyStatement(
                        new Block(List.of(), List.of(new RaiseStatement("45000", new LiteralExpression("boom", new TTextType()), List.of())), List.of()),
                        List.of(),
                        new Block(List.of(), List.of(new CloseCursorStatement("cur_items"), new CallStatement("cleanup", List.of())), List.of()))),
                List.of());

        String sql = new PostgreSqlEmitter().emitProcedure("app", "with_finally", SecurityMode.INVOKER, body);

        assertTrue(sql.contains("__titan_saved_state"));
        assertTrue(sql.contains("EXCEPTION WHEN OTHERS THEN"));
        assertTrue(sql.contains("CLOSE cur_items;"));
        assertTrue(sql.contains("CALL \"cleanup\"();"));
        assertTrue(sql.contains("RAISE EXCEPTION USING ERRCODE = __titan_saved_state, MESSAGE = __titan_saved_message;"));
    }

    @Test
    void emitsPostgresMultiCatchWithSqlStateOrClauses() {
        TryCatchFinallyStatement statement = new TryCatchFinallyStatement(
                new Block(List.of(), List.of(new RaiseStatement("22012", new LiteralExpression("oops", new TTextType()), List.of())), List.of()),
                List.of(new CatchClause("ex", "java.lang.IllegalArgumentException", List.of("22012", "22003"),
                        new Block(List.of(), List.of(new CallStatement("recover", List.of())), List.of()))),
                null);

        String sql = new PostgreSqlEmitter().visitTryCatchFinallyStatement(statement);

        assertTrue(sql.contains("WHEN SQLSTATE '22012' OR SQLSTATE '22003' THEN"));
        assertTrue(sql.contains("DECLARE ex TEXT := NULL;"));
        assertTrue(sql.contains("ex := SQLERRM;"));
        assertTrue(sql.contains("CALL \"recover\"();"));
        assertTrue(sql.contains("WHEN OTHERS THEN"));
    }

    @Test
    void emitsPostgresStringTranslationHelperExpressions() {
        PostgreSqlEmitter emitter = new PostgreSqlEmitter();

        String concatSql = emitter.visitFunctionCallExpression(new FunctionCallExpression(
                "__titan_str_concat",
                List.of(new VariableRefExpression("v_left"), new VariableRefExpression("v_right")),
                null));
        String indexOfSql = emitter.visitFunctionCallExpression(new FunctionCallExpression(
                "__titan_str_index_of",
                List.of(new VariableRefExpression("v_text"), new LiteralExpression("x", new TTextType())),
                null));
        String charCodeSql = emitter.visitFunctionCallExpression(new FunctionCallExpression(
                "__titan_char_code",
                List.of(new LiteralExpression('7', new TTextType())),
                null));
        String indexOfFromSql = emitter.visitFunctionCallExpression(new FunctionCallExpression(
                "__titan_str_index_of",
                List.of(
                        new VariableRefExpression("v_text"),
                        new LiteralExpression("x", new TTextType()),
                        new VariableRefExpression("v_from")),
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
        String startsWithOffsetSql = emitter.visitFunctionCallExpression(new FunctionCallExpression(
                "__titan_str_starts_with",
                List.of(
                        new VariableRefExpression("v_text"),
                        new LiteralExpression("pre", new TTextType()),
                        new VariableRefExpression("v_offset")),
                null));
        String matchesSql = emitter.visitFunctionCallExpression(new FunctionCallExpression(
                "__titan_str_matches",
                List.of(new VariableRefExpression("v_text"), new LiteralExpression("^[a-z]+$", new TTextType())),
                null));
        String formatSql = emitter.visitFunctionCallExpression(new FunctionCallExpression(
                "__titan_str_format",
                List.of(new LiteralExpression("%s-%s", new TTextType()), new VariableRefExpression("v_text"), new LiteralExpression("ok", new TTextType())),
                null));

        assertTrue(concatSql.equals("(COALESCE(CAST(v_left AS TEXT), 'null') || COALESCE(CAST(v_right AS TEXT), 'null'))"));
        assertEquals("ASCII('7')", charCodeSql);
        assertTrue(indexOfSql.contains("STRPOS(v_text, 'x') - 1"));
        assertTrue(indexOfFromSql.contains("GREATEST(v_from, 0)"));
        assertTrue(indexOfFromSql.contains("SUBSTRING(v_text FROM (GREATEST(v_from, 0) + 1))"));
        assertTrue(indexOfFromSql.contains("WHEN STRPOS(SUBSTRING(v_text FROM (GREATEST(v_from, 0) + 1)), 'x') = 0 THEN -1"));
        assertTrue(indexOfFromSql.contains("ELSE (GREATEST(v_from, 0) + STRPOS(SUBSTRING(v_text FROM (GREATEST(v_from, 0) + 1)), 'x') - 1)"));
        assertTrue(substringSql.contains("SUBSTRING(v_text FROM 2 FOR 3)"));
        assertTrue(foldedSubstringSql.contains("SUBSTRING(v_text FROM 3 FOR 3)"));
        assertTrue(splitSql.equals("STRING_TO_ARRAY(v_text, ',')"));
        assertTrue(startsWithOffsetSql.contains("WHEN v_offset < 0 THEN FALSE"));
        assertTrue(startsWithOffsetSql.contains("WHEN (v_offset + CHAR_LENGTH('pre')) > CHAR_LENGTH(v_text) THEN FALSE"));
        assertTrue(startsWithOffsetSql.contains("SUBSTRING(v_text FROM (v_offset + 1) FOR CHAR_LENGTH('pre')) = 'pre'"));
        assertTrue(matchesSql.equals("(v_text ~ '^[a-z]+$')"));
        assertTrue(formatSql.equals("FORMAT('%s-%s', v_text, 'ok')"));
    }

    @Test
    void emitsPostgresMathTranslationHelperExpressions() {
        PostgreSqlEmitter emitter = new PostgreSqlEmitter();

        String randomSql = emitter.visitFunctionCallExpression(new FunctionCallExpression("__titan_math_random", List.of(), null));
        String log10Sql = emitter.visitFunctionCallExpression(new FunctionCallExpression(
                "__titan_math_log10",
                List.of(new VariableRefExpression("v_num")),
                null));
        String roundSql = emitter.visitFunctionCallExpression(new FunctionCallExpression(
                "__titan_math_round",
                List.of(new VariableRefExpression("v_num")),
                null));

        assertTrue(randomSql.equals("RANDOM()"));
        assertTrue(log10Sql.equals("LOG(v_num)"));
        assertTrue(roundSql.equals("titan_runtime.java_round(v_num, 0)"));
    }

    @Test
    void emitsPostgresDateTimeTranslationHelperExpressions() {
        PostgreSqlEmitter emitter = new PostgreSqlEmitter();

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
        String plusAmount = emitter.visitFunctionCallExpression(new FunctionCallExpression(
                "__titan_time_plus_amount",
                List.of(
                        new VariableRefExpression("v_ts"),
                        new FunctionCallExpression("__titan_time_duration_of_hours", List.of(new LiteralExpression(3, new TIntType())), null)
                ),
                null));

        assertTrue(nowDate.equals("CURRENT_DATE"));
        assertTrue(plusDays.equals("(v_date + (2 * INTERVAL '1 day'))"));
        assertTrue(toDate.equals("DATE(v_ts)"));
        assertTrue(instantNow.equals("CURRENT_TIMESTAMP"));
        assertTrue(zonedNow.equals("CURRENT_TIMESTAMP"));
        assertTrue(toInstant.equals("CAST(v_ts AS TIMESTAMPTZ)"));
        assertTrue(duration.equals("(90 * INTERVAL '1 second')"));
        assertTrue(period.equals("(2 * INTERVAL '1 day')"));
        assertTrue(plusAmount.equals("(v_ts + (3 * INTERVAL '1 hour'))"));
    }

    @Test
    void emitsPostgresTemporalAmountLocalsAsIntervalVariables() {
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

        String sql = new PostgreSqlEmitter().emitProcedure("app", "temporal_locals", SecurityMode.INVOKER, body);

        assertTrue(sql.contains("v_timeout INTERVAL := (90 * INTERVAL '1 second');"));
        assertTrue(sql.contains("v_now TIMESTAMP := CURRENT_TIMESTAMP;"));
        assertTrue(sql.contains("v_later TIMESTAMP := (v_now + v_timeout);"));
    }

    @Test
    void emitsTriggerFunctionAndCreateTrigger() {
        Block body = new Block(List.of(), List.of(new ReturnStatement(null)), List.of());

        String sql = new PostgreSqlEmitter().emitTrigger(
                "app",
                "before_account_change",
                SecurityMode.INVOKER,
                new TriggerSpec("accounts", "BEFORE", List.of("INSERT", "UPDATE"), "ROW"),
                body);

        assertTrue(sql.contains("CREATE OR REPLACE FUNCTION \"app\".\"before_account_change_fn\"()"));
        assertTrue(sql.contains("RETURNS TRIGGER"));
        assertTrue(sql.contains("CREATE TRIGGER \"before_account_change_trg\""));
        assertTrue(sql.contains("BEFORE INSERT OR UPDATE ON \"app\".\"accounts\""));
        assertTrue(sql.contains("FOR EACH ROW"));
        assertTrue(sql.contains("EXECUTE FUNCTION \"app\".\"before_account_change_fn\"();"));
        assertTrue(sql.contains("RETURN NEW;"));
    }

    @Test
    void emitsDeleteTriggerReturningOldRow() {
        Block body = new Block(List.of(), List.of(new ReturnStatement(null)), List.of());

        String sql = new PostgreSqlEmitter().emitTrigger(
                "app",
                "after_account_delete",
                SecurityMode.INVOKER,
                new TriggerSpec("accounts", "AFTER", List.of("DELETE"), "ROW"),
                body);

        assertTrue(sql.contains("AFTER DELETE ON \"app\".\"accounts\""));
        assertTrue(sql.contains("RETURN OLD;"));
    }

    @Test
    void emitsStaticResetCleanupForTriggerBodies() {
        Block body = new Block(
                List.of(),
                List.of(new CallStatement("__titan_static_set", List.of(
                        new LiteralExpression("Demo#counter", new TTextType()),
                        new LiteralExpression(1, new TIntType())))),
                List.of());

        String sql = new PostgreSqlEmitter().emitTrigger(
                "app",
                "before_account_static",
                SecurityMode.INVOKER,
                new TriggerSpec("accounts", "BEFORE", List.of("INSERT"), "ROW"),
                body);

        assertTrue(sql.contains("PERFORM titan_runtime.static_reset('Demo#counter');"));
        assertTrue(sql.contains("EXCEPTION WHEN OTHERS THEN"));
    }

    @Test
    void emitsForeachArrayLoop() {
        Block body = new Block(
                List.of(new DeclareVariable("value", new TIntType(), false, null)),
                List.of(new ForEachStatement(
                        "value",
                        new TIntType(),
                        new VariableRefExpression("values"),
                        new Block(List.of(), List.of(new CallStatement("consume", List.of(new VariableRefExpression("value")))), List.of()),
                        null)),
                List.of());

        String sql = new PostgreSqlEmitter().emitProcedure("app", "foreach_values", SecurityMode.INVOKER, body);

        assertTrue(sql.contains("FOREACH v_value IN ARRAY values LOOP"));
        assertTrue(sql.contains("CALL \"consume\"(v_value);"));
    }

    @Test
    void emitsScheduledJobWithPgCron() {
        Block body = new Block(List.of(), List.of(new ReturnStatement(null)), List.of());

        String sql = new PostgreSqlEmitter().emitScheduledJob(
                "app",
                "refresh_rollups",
                SecurityMode.INVOKER,
                new ScheduledJobSpec("15 4 * * *", "nightly_refresh"),
                body);

        assertTrue(sql.contains("CREATE OR REPLACE PROCEDURE \"app\".\"refresh_rollups\"()"));
        assertTrue(sql.contains("IF EXISTS (SELECT 1 FROM pg_extension WHERE extname = 'pg_cron') THEN"));
        assertTrue(sql.contains("PERFORM cron.schedule('nightly_refresh', '15 4 * * *', 'CALL \"app\".\"refresh_rollups\"()');"));
        assertTrue(sql.contains("RAISE WARNING 'Titan scheduled job % requires pg_cron extension; procedure % was generated but not scheduled.'"));
    }

    @Test
    void rejectsInvalidCronBeforeEmittingPostgresScheduledJob() {
        Block body = new Block(List.of(), List.of(new ReturnStatement(null)), List.of());

        assertThrows(IllegalArgumentException.class, () -> new PostgreSqlEmitter().emitScheduledJob(
                "app",
                "bad_job",
                SecurityMode.INVOKER,
                new ScheduledJobSpec("15 * *", "bad_refresh"),
                body));

        assertThrows(IllegalArgumentException.class, () -> new PostgreSqlEmitter().emitScheduledJob(
                "app",
                "bad_step_job",
                SecurityMode.INVOKER,
                new ScheduledJobSpec("*/70 * * * *", "bad_step_refresh"),
                body));
    }

    @Test
    void emitsRecursiveCteOnceAndForCursorLoopUsesSelect() {
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

        SelectSql recursiveSelect = new SelectSql(
                List.of(new SelectColumn(new ColumnRefExpression("acc", "id"), null)),
                "acc",
                List.of(),
                null,
                List.of(),
                null,
                List.of(),
                null,
                null,
                null,
                List.of(new CteSpec("acc", cteQuery, true)));

        Block body = new Block(
                List.of(),
                List.of(new ForCursorStatement("rec", recursiveSelect, new Block(List.of(), List.of(), List.of()), null)),
                List.of());

        String sql = new PostgreSqlEmitter().emitProcedure("app", "scan_accounts", SecurityMode.INVOKER, body);

        assertTrue(sql.contains("WITH RECURSIVE \"acc\" AS (SELECT \"accounts\".\"id\" FROM \"accounts\") SELECT \"acc\".\"id\" FROM \"acc\""));
        assertTrue(!sql.contains("WITH RECURSIVE RECURSIVE"));
        assertTrue(sql.contains("FOR rec IN WITH RECURSIVE \"acc\" AS (SELECT \"accounts\".\"id\" FROM \"accounts\") SELECT \"acc\".\"id\" FROM \"acc\" LOOP"));
    }

    @Test
    void emitsRecordModelAsCompositeTypeAndFactoryFunction() {
        RecordModelSpec spec = new RecordModelSpec(
                "CustomerSnapshot",
                List.of(
                        new RecordModelSpec.RecordField("id", "long"),
                        new RecordModelSpec.RecordField("email", "String")));

        String sql = new PostgreSqlEmitter().emitRecordModel("app", spec);

        assertTrue(sql.contains("DROP TYPE IF EXISTS \"app\".\"__record_customer_snapshot\" CASCADE;"));
        assertTrue(sql.contains("CREATE TYPE \"app\".\"__record_customer_snapshot\" AS ("));
        assertTrue(sql.contains("\"id\" BIGINT"));
        assertTrue(sql.contains("\"email\" TEXT"));
        // B-2 (TG-BLK-005): member functions join with "__" so they can never collide with
        // another record's snake-cased type name.
        assertTrue(sql.contains("CREATE OR REPLACE FUNCTION \"app\".\"__record_customer_snapshot__new\"("));
        assertTrue(sql.contains("SECURITY INVOKER"));
    }

    @Test
    void quotesRecordFieldNamesThatArePostgresKeywords() {
        RecordModelSpec spec = new RecordModelSpec(
                "Argument",
                List.of(
                        new RecordModelSpec.RecordField("type", "String"),
                        new RecordModelSpec.RecordField("column", "String")));

        String sql = new PostgreSqlEmitter().emitRecordModel("app", spec);

        assertTrue(sql.contains("\"type\" TEXT"));
        assertTrue(sql.contains("\"column\" TEXT"));
        assertTrue(sql.contains("\"app\".\"__record_argument__new\"(p_type TEXT, p_column TEXT)"));
    }

    @Test
    void quotesCharacterLiteralsAsTextLiterals() {
        Block body = new Block(
                List.of(),
                List.of(new ReturnStatement(new LiteralExpression('@', new TTextType()))),
                List.of());

        String sql = new PostgreSqlEmitter().emitFunction("app", "directive_marker", SecurityMode.INVOKER, new TTextType(), body);

        assertTrue(sql.contains("RETURN '@';"));
    }

    @Test
    void emitsEqualsHelperAsNullSafePostgresEquality() {
        String sql = new PostgreSqlEmitter().visitFunctionCallExpression(new FunctionCallExpression(
                "equals",
                List.of(new VariableRefExpression("left"), new LiteralExpression("right", new TTextType())),
                null));

        assertEquals("(left IS NOT DISTINCT FROM 'right')", sql);
    }

    @Test
    void appliesCustomNamingPrefixesInRecordAndEnumHelpers() {
        PostgreSqlEmitter emitter = new PostgreSqlEmitter(new NamingConventionEngine("arg", "tmp", "const"));

        RecordModelSpec recordSpec = new RecordModelSpec(
                "CustomerSnapshot",
                List.of(new RecordModelSpec.RecordField("createdAt", "String")));
        String recordSql = emitter.emitRecordModel("app", recordSpec);

        EnumLookupSpec enumSpec = new EnumLookupSpec(
                "AccountState",
                List.of(new EnumLookupSpec.EnumField("display_name", "String")),
                List.of(new EnumLookupSpec.EnumMethod("getDisplayName", "String")),
                List.of(new EnumLookupSpec.EnumValue("ACTIVE", List.of("Active"))));
        String enumSql = emitter.emitEnumLookup("app", enumSpec);

        assertTrue(recordSql.contains("arg_created_at TEXT"));
        assertTrue(recordSql.contains("RETURN ROW(arg_created_at)::\"app\".\"__record_customer_snapshot\";"));
        assertTrue(enumSql.contains("(arg_enum_key TEXT)"));
        assertTrue(enumSql.contains("SECURITY INVOKER"));
        assertTrue(enumSql.contains("DECLARE tmp_value TEXT;"));
        assertTrue(enumSql.contains("RETURN tmp_value;"));
    }

    @Test
    void emitsPostgresViewWithExplicitInvokerSecurity() {
        String sql = new PostgreSqlEmitter().emitView("app", "active_accounts", "SELECT * FROM accounts WHERE active = TRUE");

        assertTrue(sql.contains("CREATE OR REPLACE VIEW \"app\".\"active_accounts\" WITH (security_invoker = true) AS"));
        assertTrue(sql.contains("SELECT * FROM accounts WHERE active = TRUE;"));
    }

    @Test
    void emitsPostgresStaticFieldHelperCalls() {
        PostgreSqlEmitter emitter = new PostgreSqlEmitter();

        String getSql = emitter.visitFunctionCallExpression(new FunctionCallExpression(
                "__titan_static_get",
                List.of(new LiteralExpression("Demo#counter", new TTextType())),
                null));
        String setSql = emitter.visitCallStatement(new CallStatement(
                "__titan_static_set",
                List.of(
                        new LiteralExpression("Demo#counter", new TTextType()),
                        new LiteralExpression(1, new TIntType()))));

        assertTrue(getSql.equals("titan_runtime.static_get('Demo#counter')"));
        assertTrue(setSql.equals("PERFORM titan_runtime.static_set('Demo#counter', CAST(1 AS TEXT));"));
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

        String sql = new PostgreSqlEmitter().emitFunction("app", "with_static", SecurityMode.INVOKER, new TIntType(), body);

        assertTrue(sql.contains("PERFORM titan_runtime.static_reset('Demo#counter');"));
        assertTrue(sql.contains("RETURN 1;"));
        assertTrue(sql.contains("EXCEPTION WHEN OTHERS THEN"));
    }

    @Test
    void emitsUnlabeledExitAndContinueForBreakContinueStatements() {
        // PL/pgSQL EXIT/CONTINUE bind to the innermost loop natively, so no labels are emitted.
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
                        new Block(
                                List.of(),
                                List.of(new WhileStatement(
                                        new BinaryOpExpression(
                                                new VariableRefExpression("v_inner"),
                                                BinaryOperator.LESS_THAN,
                                                new LiteralExpression(10, new TIntType())),
                                        innerBody,
                                        null)),
                                List.of()),
                        null)),
                List.of());

        String sql = new PostgreSqlEmitter().emitProcedure("app", "nested_loops", SecurityMode.INVOKER, body);

        assertTrue(sql.contains("CONTINUE;"), sql);
        assertTrue(sql.contains("EXIT;"), sql);
        assertFalse(sql.contains("titan_loop_"), sql);
    }

    @Test
    void emitsNativeForRangeLoop() {
        Block body = new Block(
                List.of(new DeclareVariable("v_i", new TIntType(), false, null)),
                List.of(new ForRangeStatement(
                        "v_i",
                        new LiteralExpression(1, new TIntType()),
                        new BinaryOpExpression(
                                new VariableRefExpression("p_limit"),
                                BinaryOperator.SUBTRACT,
                                new LiteralExpression(1, new TIntType())),
                        new Block(
                                List.of(),
                                List.of(new CallStatement("do_work", List.of(new VariableRefExpression("v_i")))),
                                List.of()),
                        null)),
                List.of());

        String sql = new PostgreSqlEmitter().emitProcedure("app", "range_loop", SecurityMode.INVOKER, body);

        assertTrue(sql.contains("FOR v_i IN 1..(p_limit - 1) LOOP"), sql);
        assertTrue(sql.contains("END LOOP;"), sql);
        assertFalse(sql.contains("WHILE"), sql);
    }

    @Test
    void emitsRaiseWithStringLiteralMessageByteIdentically() {
        // F-10 (plan 2.2): string-literal messages keep the exact pre-expression shape.
        String sql = new PostgreSqlEmitter().visitRaiseStatement(
                new RaiseStatement("45000", new LiteralExpression("boom", new TTextType()), List.of()));

        assertEquals("RAISE EXCEPTION USING ERRCODE = '45000', MESSAGE = 'boom';", sql);
    }

    @Test
    void emitsRaiseWithDynamicMessageThroughUsingClause() {
        // F-10 (plan 2.2): dynamic messages are emitted via USING MESSAGE = <expr> (never via
        // the RAISE format string, where '%' is a placeholder), null-guarded with COALESCE
        // because RAISE rejects a NULL option value.
        String sql = new PostgreSqlEmitter().visitRaiseStatement(new RaiseStatement(
                "45002",
                new FunctionCallExpression("__titan_str_concat", List.of(
                        new LiteralExpression("failed: ", new TTextType()),
                        new VariableRefExpression("v_reason")), null),
                List.of()));

        assertEquals("RAISE EXCEPTION USING ERRCODE = '45002', MESSAGE = "
                + "COALESCE((COALESCE(CAST('failed: ' AS TEXT), 'null') || COALESCE(CAST(v_reason AS TEXT), 'null')), 'Java throw');", sql);
    }

    @Test
    void emitsCastExpressionsWithJavaTruncationSemantics() {
        PostgreSqlEmitter emitter = new PostgreSqlEmitter();

        // Numeric widening: plain CAST.
        assertEquals("CAST(v_small AS BIGINT)", emitter.visitCastExpression(
                new CastExpression(new VariableRefExpression("v_small"), new TBigintType())));
        assertEquals("CAST(v_small AS NUMERIC(38,10))", emitter.visitCastExpression(
                new CastExpression(new VariableRefExpression("v_small"), new TNumericType(38, 10))));

        // Fractional-to-integral: Java truncates toward zero, so the CAST composes TRUNC
        // (a bare CAST would round 7.9 to 8 where Java yields 7).
        assertEquals("CAST(TRUNC(v_ratio) AS BIGINT)", emitter.visitCastExpression(
                new CastExpression(new VariableRefExpression("v_ratio"), new TBigintType(), true)));
        assertEquals("CAST(TRUNC(v_ratio) AS INTEGER)", emitter.visitCastExpression(
                new CastExpression(new VariableRefExpression("v_ratio"), new TIntType(), true)));
    }

    /**
     * B-10 (TG-BLK-012): PostgreSQL's {@code boolean::text} already renders {@code 'true'}/
     * {@code 'false'}, matching Java's {@link Boolean#toString(boolean)}, so the boolean-to-text
     * coercion is a no-op here — a boolean reaching a text context keeps its native
     * {@code CAST(... AS TEXT)} and must NOT pick up the MySQL {@code IF(...)} form. This pins that
     * the shared coercion path did not regress the already-correct PostgreSQL rendering.
     */
    @Test
    void booleanToTextKeepsNativePostgresCastRendering() {
        PostgreSqlEmitter emitter = new PostgreSqlEmitter();

        // String.valueOf(boolean predicate): native CAST AS TEXT (boolean::text => true/false).
        String valueOfBoolean = emitter.visitCastExpression(new CastExpression(
                new BinaryOpExpression(
                        new VariableRefExpression("v_left"),
                        BinaryOperator.LESS_THAN_OR_EQUAL,
                        new VariableRefExpression("v_right")),
                new TTextType()));
        assertEquals("CAST((v_left <= v_right) AS TEXT)", valueOfBoolean);
        assertFalse(valueOfBoolean.contains("IF("));

        // A boolean literal in a String + concatenation chain: unchanged native rendering.
        String concatWithBoolean = emitter.visitFunctionCallExpression(new FunctionCallExpression(
                "__titan_str_concat",
                List.of(
                        new LiteralExpression("flag=", new TTextType()),
                        new LiteralExpression(true, new TBooleanType())),
                null));
        assertEquals(
                "(COALESCE(CAST('flag=' AS TEXT), 'null') || COALESCE(CAST(TRUE AS TEXT), 'null'))",
                concatWithBoolean);
        assertFalse(concatWithBoolean.contains("IF("));
    }

    @Test
    void emitsControlCharacterLiteralsAsEscapeStrings() {
        // B-3 (TG-BLK-006): control characters must never reach the SQL as raw bytes —
        // indentMultiline treats an embedded LF as a line break and "indents" it, silently
        // turning '\n' into a five-character literal. Control characters render as E''
        // escape strings; control-free literals keep the historical plain rendering.
        PostgreSqlEmitter emitter = new PostgreSqlEmitter();

        assertEquals("E'\\n'", emitter.visitLiteralExpression(new LiteralExpression('\n', new TTextType())));
        assertEquals("E'\\t'", emitter.visitLiteralExpression(new LiteralExpression('\t', new TTextType())));
        assertEquals("E'\\r'", emitter.visitLiteralExpression(new LiteralExpression('\r', new TTextType())));
        // Control characters without a named escape use two-digit hex escapes.
        assertEquals("E'\\x0b'", emitter.visitLiteralExpression(new LiteralExpression('\u000B', new TTextType())));
        // String literals with embedded control characters: backslashes and quotes must be
        // escaped under E'' rules too (E'' interprets backslashes, unlike plain '...').
        assertEquals("E'a\\nb''c\\\\d'", emitter.visitLiteralExpression(
                new LiteralExpression("a\nb'c\\d", new TTextType())));

        // Control-free literals keep the historical rendering byte-for-byte (no E prefix,
        // no backslash doubling under standard_conforming_strings).
        assertEquals("'a'", emitter.visitLiteralExpression(new LiteralExpression('a', new TTextType())));
        assertEquals("'it''s a\\b'", emitter.visitLiteralExpression(
                new LiteralExpression("it's a\\b", new TTextType())));
    }

    @Test
    void emitsGeneratedKeyReadAsExecuteReturningIntoUsing() {
        // JDBC I-7 generated-key recovery (§6.3 / I-R8): the PG form is a single dynamic
        // EXECUTE '<insert> RETURNING "<col>"' INTO <keyLocal> USING <params>; — the inserted row's
        // own auto-increment column (resolved from the Catalog, NOT the JDBC ordinal 1) is read, so it
        // is immune to an AFTER INSERT trigger that advances any other sequence. The ? placeholders are
        // rewritten to $n bound by USING (never spliced), and the RETURNING column is double-quoted.
        GeneratedKeyReadStatement node = new GeneratedKeyReadStatement(
                new RawSql("INSERT INTO invoices (customer_id, amount) VALUES (?, ?)",
                        List.of("p_customer_id", "p_amount"), "POSTGRESQL"),
                "id",
                "__titan_genkey1");

        String emitted = new PostgreSqlEmitter().visitGeneratedKeyReadStatement(node);

        assertEquals(
                "EXECUTE 'INSERT INTO invoices (customer_id, amount) VALUES ($1, $2) RETURNING \"id\"' "
                        + "INTO __titan_genkey1 USING p_customer_id, p_amount;",
                emitted);
    }

    @Test
    void emitsGeneratedKeyReadWithoutParametersOmitsUsing() {
        // A parameterless INSERT recovers its key with no USING clause (the bound-value list is empty).
        GeneratedKeyReadStatement node = new GeneratedKeyReadStatement(
                new RawSql("INSERT INTO invoices (note) VALUES ('hi')", List.of(), "POSTGRESQL"),
                "id",
                "__titan_genkey1");

        String emitted = new PostgreSqlEmitter().visitGeneratedKeyReadStatement(node);

        assertEquals(
                "EXECUTE 'INSERT INTO invoices (note) VALUES (''hi'') RETURNING \"id\"' INTO __titan_genkey1;",
                emitted);
    }
}
