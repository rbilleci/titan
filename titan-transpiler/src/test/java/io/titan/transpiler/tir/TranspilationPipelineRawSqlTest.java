package io.titan.transpiler.tir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TranspilationPipelineRawSqlTest {

    @TempDir
    Path tempDir;

    @Test
    void emitsSqlNullComparisonsForJavaNullEquality() throws Exception {
        Path source = tempDir.resolve("NullComparisonFunction.java");
        Files.writeString(source, """
                import titan.dsl.StoredFunction;

                class NullComparisonFunction {
                    @StoredFunction
                    static String normalize(String value) {
                        if (value == null) {
                            return "empty";
                        }
                        if (value != null) {
                            return value;
                        }
                        return "unreachable";
                    }
                }
                """);

        List<TranspilationPipeline.GeneratedSql> generated = new TranspilationPipeline().transpile(
                List.of(source),
                List.of(),
                List.of("postgresql", "mysql"),
                List.of("app"),
                true);

        String postgresSql = generated.stream()
                .filter(sql -> sql.target().equals("postgresql"))
                .findFirst()
                .orElseThrow()
                .sql();
        String mysqlSql = generated.stream()
                .filter(sql -> sql.target().equals("mysql"))
                .findFirst()
                .orElseThrow()
                .sql();

        assertTrue(postgresSql.contains("p_value IS NULL"));
        assertTrue(postgresSql.contains("p_value IS NOT NULL"));
        assertFalse(postgresSql.contains("p_value = NULL"));
        assertFalse(postgresSql.contains("p_value <> NULL"));

        assertTrue(mysqlSql.contains("p_value IS NULL"));
        assertTrue(mysqlSql.contains("p_value IS NOT NULL"));
        assertFalse(mysqlSql.contains("p_value = NULL"));
        assertFalse(mysqlSql.contains("p_value <> NULL"));
    }

    @Test
    void emitsDslPromotionWarningForGenericSqlAnnotation() throws Exception {
        Path source = tempDir.resolve("RawSqlWarningDemo.java");
        Files.writeString(source, """
                import titan.dsl.SQL;
                import titan.dsl.SqlDialect;
                import titan.dsl.StoredProcedure;

                class RawSqlWarningDemo {
                    @StoredProcedure
                    @SQL(dialect = SqlDialect.POSTGRESQL, value = "SELECT * FROM accounts WHERE id = :accountId")
                    static void run(long accountId) {}
                }
                """);

        TranspilationPipeline pipeline = new TranspilationPipeline();
        pipeline.transpile(
                List.of(source),
                List.of(),
                List.of("postgresql"),
                List.of("app"),
                true);

        String warnings = pipeline.warnings().stream()
                .map(io.titan.transpiler.diagnostics.TitanDiagnostic::render)
                .reduce("", (left, right) -> left + right + "\n");
        assertTrue(warnings.contains("TITAN-W001"));
        assertTrue(warnings.contains("RawSqlWarningDemo.java:"));
    }

    @Test
    void appendsDialectMatchedRawSqlAnnotationsToGeneratedRoutine() throws Exception {
        Path source = tempDir.resolve("RawSqlDemo.java");
        Files.writeString(source, """
                import titan.dsl.SQL;
                import titan.dsl.SqlDialect;
                import titan.dsl.StoredProcedure;

                class RawSqlDemo {
                    @StoredProcedure
                    @SQL(dialect = SqlDialect.POSTGRESQL, value = "SELECT * FROM accounts WHERE id = :accountId")
                    static void run(long accountId, String status) {
                        @SQL(dialect = SqlDialect.MYSQL, value = "UPDATE accounts SET status = :status WHERE id = :accountId")
                        String mysqlOnly = "ok";
                    }
                }
                """);

        TranspilationPipeline pipeline = new TranspilationPipeline();
        List<TranspilationPipeline.GeneratedSql> generated = pipeline.transpile(
                List.of(source),
                List.of(),
                List.of("postgresql", "mysql"),
                List.of("app"),
                true);

        assertEquals(2, generated.size());

        String postgresSql = generated.stream()
                .filter(sql -> sql.target().equals("postgresql"))
                .findFirst()
                .orElseThrow()
                .sql();
        String mysqlSql = generated.stream()
                .filter(sql -> sql.target().equals("mysql"))
                .findFirst()
                .orElseThrow()
                .sql();

        assertTrue(postgresSql.contains("EXECUTE 'SELECT * FROM accounts WHERE id = $1' USING p_account_id;"));
        assertTrue(!postgresSql.contains("UPDATE accounts SET status"));

        assertTrue(mysqlSql.contains("SET @titan_p1 = p_status;"));
        assertTrue(mysqlSql.contains("SET @titan_p2 = p_account_id;"));
        assertTrue(mysqlSql.contains("PREPARE titan_stmt_1 FROM 'UPDATE accounts SET status = ? WHERE id = ?';"));
        assertTrue(!mysqlSql.contains("SELECT * FROM accounts WHERE id = $1"));
    }

    @Test
    void rewritesRawSqlNamedParametersWithoutTouchingQuotedOrCommentedText() throws Exception {
        Path source = tempDir.resolve("RawSqlQuotedParameterDemo.java");
        Files.writeString(source, """
                import titan.dsl.SQL;
                import titan.dsl.SqlDialect;
                import titan.dsl.StoredProcedure;

                class RawSqlQuotedParameterDemo {
                    @StoredProcedure
                    @SQL(dialect = SqlDialect.POSTGRESQL, value = "SELECT :accountId AS bound_value, ':accountId' AS literal_value -- :accountId should not be rewritten in comments")
                    static void pg(long accountId) {}

                    @StoredProcedure
                    @SQL(dialect = SqlDialect.MYSQL, value = "UPDATE accounts SET status = :status, note = ':status' WHERE id = :accountId /* :status in comments must stay untouched */")
                    static void mysql(long accountId, String status) {}
                }
                """);

        List<TranspilationPipeline.GeneratedSql> generated = new TranspilationPipeline().transpile(
                List.of(source),
                List.of(),
                List.of("postgresql", "mysql"),
                List.of("app"),
                true);

        String postgresSql = generated.stream()
                .filter(sql -> sql.target().equals("postgresql") && sql.methodName().equals("pg"))
                .findFirst()
                .orElseThrow()
                .sql();
        String mysqlSql = generated.stream()
                .filter(sql -> sql.target().equals("mysql") && sql.methodName().equals("mysql"))
                .findFirst()
                .orElseThrow()
                .sql();

        assertTrue(postgresSql.contains("EXECUTE 'SELECT $1 AS bound_value,"));
        assertTrue(postgresSql.contains(":accountId"));
        assertTrue(!postgresSql.contains("'$1' AS literal_value"));
        assertTrue(postgresSql.contains("-- :accountId should not be rewritten in comments"));

        assertTrue(mysqlSql.contains("SET @titan_p1 = p_status;"));
        assertTrue(mysqlSql.contains("SET @titan_p2 = p_account_id;"));
        assertTrue(mysqlSql.contains("SET status = ?,"));
        assertTrue(mysqlSql.contains(":status"));
        assertTrue(!mysqlSql.contains("note = '?'"));
        assertTrue(mysqlSql.contains("WHERE id = ?"));
        assertTrue(mysqlSql.contains("/* :status in comments must stay untouched */"));
    }

    @Test
    void disambiguatesCollidingNormalizedRoutineParameterNamesThroughPipeline() throws Exception {
        Path source = tempDir.resolve("RoutineParameterCollisionDemo.java");
        Files.writeString(source, """
                import titan.dsl.SQL;
                import titan.dsl.SqlDialect;
                import titan.dsl.StoredFunction;
                import titan.dsl.StoredProcedure;

                class RoutineParameterCollisionDemo {
                    @StoredFunction
                    static int sum(int userId, int user_id) {
                        return userId + user_id;
                    }

                    @StoredProcedure
                    @SQL(dialect = SqlDialect.POSTGRESQL, value = "SELECT * FROM accounts WHERE owner_id = :userId AND reviewer_id = :user_id")
                    static void pg(int userId, int user_id) {}

                    @StoredProcedure
                    @SQL(dialect = SqlDialect.MYSQL, value = "UPDATE accounts SET owner_id = :userId WHERE reviewer_id = :user_id")
                    static void mysql(int userId, int user_id) {}
                }
                """);

        List<TranspilationPipeline.GeneratedSql> generated = new TranspilationPipeline().transpile(
                List.of(source),
                List.of(),
                List.of("postgresql", "mysql"),
                List.of("app"),
                true);

        String postgresFunctionSql = generated.stream()
                .filter(sql -> sql.target().equals("postgresql") && sql.methodName().equals("sum"))
                .findFirst()
                .orElseThrow()
                .sql();
        String postgresRawSql = generated.stream()
                .filter(sql -> sql.target().equals("postgresql") && sql.methodName().equals("pg"))
                .findFirst()
                .orElseThrow()
                .sql();
        String mysqlFunctionSql = generated.stream()
                .filter(sql -> sql.target().equals("mysql") && sql.methodName().equals("sum"))
                .findFirst()
                .orElseThrow()
                .sql();
        String mysqlRawSql = generated.stream()
                .filter(sql -> sql.target().equals("mysql") && sql.methodName().equals("mysql"))
                .findFirst()
                .orElseThrow()
                .sql();

        assertTrue(postgresFunctionSql.contains("CREATE OR REPLACE FUNCTION \"app\".\"sum\"(p_user_id INTEGER, p_user_id_2 INTEGER)"));
        assertTrue(postgresFunctionSql.contains("RETURN (p_user_id + p_user_id_2);"));
        assertTrue(postgresRawSql.contains("EXECUTE 'SELECT * FROM accounts WHERE owner_id = $1 AND reviewer_id = $2' USING p_user_id, p_user_id_2;"));

        assertTrue(mysqlFunctionSql.contains("CREATE FUNCTION `app`.`sum`(p_user_id INT, p_user_id_2 INT)"));
        assertTrue(mysqlFunctionSql.contains("SET __titan_return_value = (p_user_id + p_user_id_2);"));
        assertTrue(mysqlRawSql.contains("SET @titan_p1 = p_user_id;"));
        assertTrue(mysqlRawSql.contains("SET @titan_p2 = p_user_id_2;"));
        assertTrue(mysqlRawSql.contains("PREPARE titan_stmt_1 FROM 'UPDATE accounts SET owner_id = ? WHERE reviewer_id = ?';"));
    }

    @Test
    void renamesLocalsThatCollideWithObservabilityHelperIdentifiersThroughPipeline() throws Exception {
        Path source = tempDir.resolve("ObservabilityIdentifierCollisionDemo.java");
        Files.writeString(source, """
                import titan.dsl.StoredProcedure;

                class ObservabilityIdentifierCollisionDemo {
                    @StoredProcedure
                    static void run() {
                        int __titan_started_at = 1;
                        __titan_started_at = __titan_started_at + 1;
                    }
                }
                """);

        List<TranspilationPipeline.GeneratedSql> generated = new TranspilationPipeline().transpile(
                List.of(source),
                List.of(),
                List.of("postgresql", "mysql"),
                List.of("app"),
                true,
                List.of(),
                true,
                List.of(),
                false);

        String postgresSql = generated.stream()
                .filter(sql -> sql.target().equals("postgresql"))
                .findFirst()
                .orElseThrow()
                .sql();
        String mysqlSql = generated.stream()
                .filter(sql -> sql.target().equals("mysql"))
                .findFirst()
                .orElseThrow()
                .sql();

        assertTrue(postgresSql.contains("__titan_started_at_2 INTEGER;"));
        assertTrue(postgresSql.contains("__titan_started_at_2 := 1;"));
        assertTrue(postgresSql.contains("__titan_started_at_2 := (__titan_started_at_2 + 1);"));
        assertTrue(postgresSql.contains("__titan_started_at TIMESTAMPTZ := clock_timestamp();"));

        assertTrue(mysqlSql.contains("DECLARE __titan_started_at_2 INT;"));
        assertTrue(mysqlSql.contains("SET __titan_started_at_2 = 1;"));
        assertTrue(mysqlSql.contains("SET __titan_started_at_2 = (__titan_started_at_2 + 1);"));
        assertTrue(mysqlSql.contains("DECLARE __titan_started_at TIMESTAMP(6) DEFAULT CURRENT_TIMESTAMP(6);"));
    }

    @Test
    void renamesGeneratedMySqlLoopHelpersWhenTheyCollideWithUserLocalsThroughPipeline() throws Exception {
        Path source = tempDir.resolve("LoopHelperCollisionDemo.java");
        Files.writeString(source, """
                import titan.dsl.StoredProcedure;

                class LoopHelperCollisionDemo {
                    @StoredProcedure
                    static void run(int[] values) {
                        boolean done_value = false;
                        boolean cursor_open_value = false;
                        int cur_value = 0;
                        String titan_iter_value = "seed";
                        int iter_value = 1;

                        for (int value : values) {
                            cur_value = value;
                        }
                    }
                }
                """);

        List<TranspilationPipeline.GeneratedSql> generated = new TranspilationPipeline().transpile(
                List.of(source),
                List.of(),
                List.of("postgresql", "mysql"),
                List.of("app"),
                true);

        String mysqlSql = generated.stream()
                .filter(sql -> sql.target().equals("mysql"))
                .findFirst()
                .orElseThrow()
                .sql();

        assertTrue(mysqlSql.contains("DECLARE v_done_value BOOLEAN;"));
        assertTrue(mysqlSql.contains("DECLARE v_cursor_open_value BOOLEAN;"));
        assertTrue(mysqlSql.contains("DECLARE v_cur_value INT;"));
        assertTrue(mysqlSql.contains("DECLARE v_titan_iter_value TEXT;"));
        assertTrue(mysqlSql.contains("DECLARE v_iter_value INT;"));
        assertTrue(mysqlSql.contains("SET v_done_value = FALSE;"));
        assertTrue(mysqlSql.contains("SET v_cursor_open_value = FALSE;"));
        assertTrue(mysqlSql.contains("SET v_cur_value = 0;"));
        assertTrue(mysqlSql.contains("SET v_titan_iter_value = 'seed';"));
        assertTrue(mysqlSql.contains("SET v_iter_value = 1;"));

        assertTrue(mysqlSql.contains("CREATE TEMPORARY TABLE titan_iter_v_value (value INT);"));
        assertTrue(mysqlSql.contains("DECLARE cur_v_value CURSOR FOR SELECT value FROM titan_iter_v_value;"));
        assertTrue(mysqlSql.contains("DECLARE done_v_value BOOLEAN DEFAULT FALSE;"));
        assertTrue(mysqlSql.contains("DECLARE cursor_open_v_value BOOLEAN DEFAULT FALSE;"));
        assertTrue(mysqlSql.contains("iter_v_value: LOOP"));
        assertTrue(mysqlSql.contains("FETCH cur_v_value INTO v_value;"));
        assertTrue(mysqlSql.contains("SET v_cur_value = v_value;"));
        assertTrue(mysqlSql.contains("DROP TEMPORARY TABLE IF EXISTS titan_iter_v_value;"));
    }

    @Test
    void gatesSystemOutPrintlnBehindDebugModeFlag() throws Exception {
        Path source = tempDir.resolve("DebugPrintPipelineDemo.java");
        Files.writeString(source, """
                import titan.dsl.StoredProcedure;

                class DebugPrintPipelineDemo {
                    @StoredProcedure
                    static void run() {
                        System.out.println("hello");
                    }
                }
                """);

        TranspilationPipeline pipeline = new TranspilationPipeline();

        String defaultSql = pipeline.transpile(
                        List.of(source),
                        List.of(),
                        List.of("postgresql"),
                        List.of("app"),
                        true)
                .getFirst()
                .sql();

        String debugSql = pipeline.transpile(
                        List.of(source),
                        List.of(),
                        List.of("postgresql"),
                        List.of("app"),
                        true,
                        List.of(),
                        false,
                        List.of(),
                        true)
                .getFirst()
                .sql();

        assertTrue(!defaultSql.contains("RAISE NOTICE"));
        assertTrue(debugSql.contains("RAISE NOTICE '%', 'hello';"));
    }

    @Test
    void rejectsSensitiveColumnUsageWhenDebugPrintOutputIsPresent() throws Exception {
        Path source = tempDir.resolve("SensitiveDebugOutputDemo.java");
        Files.writeString(source, """
                import titan.dsl.*;
                import static titan.dsl.DSL.*;

                class SensitiveDebugOutputDemo {
                    static final AccountsTable ACCOUNTS = new AccountsTable();

                    @StoredProcedure
                    static void run(String email) {
                        select(ACCOUNTS.EMAIL).from(ACCOUNTS).where(ACCOUNTS.EMAIL.eq(email)).fetchOne();
                        System.out.println("debug");
                    }

                    static final class AccountsTable extends Table<Object> {
                        final Column<String> EMAIL = column("email", SQLType.VARCHAR, Nullability.NOT_NULL);

                        AccountsTable() {
                            super("accounts", "public");
                        }
                    }
                }
                """);

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> new TranspilationPipeline().transpile(
                        List.of(source),
                        List.of(),
                        List.of("postgresql"),
                        List.of("app"),
                        true,
                        List.of(),
                        false,
                        List.of("accounts.email"),
                        true)
        );

        assertTrue(error.getMessage().contains("TITAN-E006"));
        assertTrue(error.getMessage().contains("accounts.email"));
    }

    @Test
    void lowersNamedCteVariableThroughPipeline() throws Exception {
        Path source = tempDir.resolve("NamedCteVariablePipelineDemo.java");
        Files.writeString(source, """
                import titan.dsl.*;
                import static titan.dsl.DSL.*;

                class NamedCteVariablePipelineDemo {
                    static final AccountsTable ACCOUNTS = new AccountsTable();

                    @StoredProcedure
                    static void run() {
                        CommonTableExpression<Object> activeAccounts = name("active_accounts")
                                .as(select(ACCOUNTS.ID)
                                        .from(ACCOUNTS)
                                        .where(ACCOUNTS.ID.gt(0)));

                        with(activeAccounts)
                                .select(ACCOUNTS.ID)
                                .from(ACCOUNTS)
                                .fetch();
                    }

                    static final class AccountsTable extends Table<Object> {
                        final Column<Integer> ID = column("id", SQLType.INTEGER, Nullability.NOT_NULL);

                        AccountsTable() {
                            super("accounts", "public");
                        }
                    }
                }
                """);

        List<TranspilationPipeline.GeneratedSql> generated = new TranspilationPipeline().transpile(
                List.of(source),
                List.of(),
                List.of("postgresql", "mysql"),
                List.of("app"),
                true);

        assertEquals(2, generated.size());

        String postgresSql = generated.stream()
                .filter(sql -> sql.target().equals("postgresql"))
                .findFirst()
                .orElseThrow()
                .sql();
        String mysqlSql = generated.stream()
                .filter(sql -> sql.target().equals("mysql"))
                .findFirst()
                .orElseThrow()
                .sql();

        assertTrue(postgresSql.toLowerCase().contains("with \"active_accounts\" as"));
        assertTrue(mysqlSql.toLowerCase().contains("with `active_accounts` as"));
    }

    @Test
    void lowersSelectFromInlineViewVariableThroughPipeline() throws Exception {
        Path source = tempDir.resolve("InlineViewVariablePipelineDemo.java");
        Files.writeString(source, """
                import titan.dsl.*;
                import static titan.dsl.DSL.*;

                class InlineViewVariablePipelineDemo {
                    static final AccountsTable ACCOUNTS = new AccountsTable();

                    @StoredProcedure
                    static void run() {
                        InlineView<Object> activeAccounts = defineInlineView(
                                select(ACCOUNTS.ID, ACCOUNTS.EMAIL)
                                        .from(ACCOUNTS)
                                        .where(ACCOUNTS.ID.gt(0))
                        );
                        Column<Integer> activeId = activeAccounts.field("id", SQLType.INTEGER, Nullability.NOT_NULL);
                        Column<String> activeEmail = activeAccounts.field("email", SQLType.VARCHAR, Nullability.NULLABLE);

                        select(activeId, activeEmail)
                                .from(activeAccounts)
                                .orderBy(activeEmail.asc())
                                .fetch();
                    }

                    static final class AccountsTable extends Table<Object> {
                        final Column<Integer> ID = column("id", SQLType.INTEGER, Nullability.NOT_NULL);
                        final Column<String> EMAIL = column("email", SQLType.VARCHAR, Nullability.NULLABLE);

                        AccountsTable() {
                            super("accounts", "public");
                        }
                    }
                }
                """);

        List<TranspilationPipeline.GeneratedSql> generated = new TranspilationPipeline().transpile(
                List.of(source),
                List.of(),
                List.of("postgresql", "mysql"),
                List.of("app"),
                true);

        assertEquals(2, generated.size());

        String postgresSql = generated.stream()
                .filter(sql -> sql.target().equals("postgresql"))
                .findFirst()
                .orElseThrow()
                .sql();
        String mysqlSql = generated.stream()
                .filter(sql -> sql.target().equals("mysql"))
                .findFirst()
                .orElseThrow()
                .sql();

        assertTrue(postgresSql.toLowerCase().contains("with \"active_accounts\" as"));
        assertTrue(postgresSql.toLowerCase().contains("select \"active_accounts\".\"id\", \"active_accounts\".\"email\""));
        assertFalse(postgresSql.contains("defineInlineView"));
        assertFalse(postgresSql.contains("field(activeAccounts"));
        assertTrue(mysqlSql.toLowerCase().contains("with `active_accounts` as"));
        assertTrue(mysqlSql.toLowerCase().contains("select `active_accounts`.`id`, `active_accounts`.`email`"));
        assertFalse(mysqlSql.contains("defineInlineView"));
        assertFalse(mysqlSql.contains("field(activeAccounts"));
    }

    @Test
    void manglesMySqlRoutineNamesForOverloadedEntryPoints() throws Exception {
        Path source = tempDir.resolve("OverloadDemo.java");
        Files.writeString(source, """
                import titan.dsl.StoredProcedure;

                class OverloadDemo {
                    @StoredProcedure
                    static void calc(int value) {
                    }

                    @StoredProcedure
                    static void calc(String value) {
                    }
                }
                """);

        TranspilationPipeline pipeline = new TranspilationPipeline();
        List<TranspilationPipeline.GeneratedSql> generated = pipeline.transpile(
                List.of(source),
                List.of(),
                List.of("postgresql", "mysql"),
                List.of("app"),
                true);

        assertEquals(4, generated.size());

        List<String> postgresRoutines = generated.stream()
                .filter(sql -> sql.target().equals("postgresql"))
                .map(TranspilationPipeline.GeneratedSql::sql)
                .toList();
        assertTrue(postgresRoutines.stream().allMatch(sql -> sql.contains("CREATE OR REPLACE PROCEDURE \"app\".\"calc\"(")));

        List<String> mysqlRoutines = generated.stream()
                .filter(sql -> sql.target().equals("mysql"))
                .map(TranspilationPipeline.GeneratedSql::sql)
                .toList();
        assertTrue(mysqlRoutines.stream().anyMatch(sql -> sql.contains("CREATE PROCEDURE `app`.`calc__int`(")));
        assertTrue(mysqlRoutines.stream().anyMatch(sql -> sql.contains("CREATE PROCEDURE `app`.`calc__java_lang_string`(")));
    }

    @Test
    void rewritesOverloadedIntraProcedureCallSitesForMySql() throws Exception {
        Path source = tempDir.resolve("OverloadCallDemo.java");
        Files.writeString(source, """
                import titan.dsl.StoredProcedure;

                class OverloadCallDemo {
                    @StoredProcedure
                    static void root() {
                        calc(42);
                        calc("ok");
                    }

                    @StoredProcedure
                    static void calc(int value) {
                    }

                    @StoredProcedure
                    static void calc(String value) {
                    }
                }
                """);

        List<TranspilationPipeline.GeneratedSql> generated = new TranspilationPipeline().transpile(
                List.of(source),
                List.of(),
                List.of("mysql"),
                List.of("app"),
                true);

        String rootSql = generated.stream()
                .filter(sql -> sql.target().equals("mysql") && sql.methodName().equals("root"))
                .findFirst()
                .orElseThrow()
                .sql();

        assertTrue(rootSql.contains("CALL `calc__int`(42);"));
        assertTrue(rootSql.contains("CALL `calc__java_lang_string`('ok');"));
    }

    @Test
    void rewritesOverloadedStaticExpressionCallSitesForMySql() throws Exception {
        Path source = tempDir.resolve("OverloadExpressionCallDemo.java");
        Files.writeString(source, """
                import titan.dsl.StoredFunction;

                class OverloadExpressionCallDemo {
                    @StoredFunction
                    static int root() {
                        return OverloadExpressionCallDemo.calc(42) + OverloadExpressionCallDemo.calc("ok");
                    }

                    @StoredFunction
                    static int calc(int value) {
                        return value;
                    }

                    @StoredFunction
                    static int calc(String value) {
                        return 7;
                    }
                }
                """);

        List<TranspilationPipeline.GeneratedSql> generated = new TranspilationPipeline().transpile(
                List.of(source),
                List.of(),
                List.of("mysql"),
                List.of("app"),
                true);

        String rootSql = generated.stream()
                .filter(sql -> sql.target().equals("mysql") && sql.methodName().equals("root"))
                .findFirst()
                .orElseThrow()
                .sql();

        assertTrue(rootSql.contains("calc__int(42)"), rootSql);
        assertTrue(rootSql.contains("calc__java_lang_string('ok')"), rootSql);
    }

    @Test
    void emitsPostgresTriggerArtifactsFromTriggerAnnotation() throws Exception {
        Path source = tempDir.resolve("TriggerDemo.java");
        Files.writeString(source, """
                import titan.dsl.Trigger;
                import titan.dsl.TriggerEvent;
                import titan.dsl.TriggerTiming;

                class TriggerDemo {
                    @Trigger(table = "accounts", timing = TriggerTiming.BEFORE, event = { TriggerEvent.INSERT })
                    static void beforeInsert() {
                    }
                }
                """);

        List<TranspilationPipeline.GeneratedSql> generated = new TranspilationPipeline().transpile(
                List.of(source),
                List.of(),
                List.of("postgresql"),
                List.of("app"),
                true);

        assertEquals(1, generated.size());
        String sql = generated.getFirst().sql();
        assertTrue(sql.contains("CREATE OR REPLACE FUNCTION \"app\".\"before_insert_fn\"()"));
        assertTrue(sql.contains("CREATE TRIGGER \"before_insert_trg\""));
        assertTrue(sql.contains("BEFORE INSERT ON \"app\".\"accounts\""));
    }

    @Test
    void emitsMySqlTriggerArtifactsFromTriggerAnnotation() throws Exception {
        Path source = tempDir.resolve("TriggerMySqlDemo.java");
        Files.writeString(source, """
                import titan.dsl.Trigger;
                import titan.dsl.TriggerEvent;
                import titan.dsl.TriggerTiming;

                class TriggerMySqlDemo {
                    @Trigger(table = "accounts", timing = TriggerTiming.AFTER, event = { TriggerEvent.INSERT, TriggerEvent.UPDATE })
                    static void afterWrite() {
                    }
                }
                """);

        List<TranspilationPipeline.GeneratedSql> generated = new TranspilationPipeline().transpile(
                List.of(source),
                List.of(),
                List.of("mysql"),
                List.of("app"),
                true);

        assertEquals(1, generated.size());
        String sql = generated.getFirst().sql();
        assertTrue(sql.contains("-- TITAN-W004: MySQL triggers do not support SQL SECURITY INVOKER; emitting DEFINER trigger owned by CURRENT_USER."));
        assertTrue(sql.contains("CREATE DEFINER = CURRENT_USER TRIGGER `after_write_insert_trg`"));
        assertTrue(sql.contains("AFTER INSERT ON `app`.`accounts`"));
        assertTrue(sql.contains("CREATE DEFINER = CURRENT_USER TRIGGER `after_write_update_trg`"));
        assertTrue(sql.contains("AFTER UPDATE ON `app`.`accounts`"));
    }

    @Test
    void emitsScheduledJobArtifactsForPostgresAndMySql() throws Exception {
        Path source = tempDir.resolve("ScheduledJobDemo.java");
        Files.writeString(source, """
                import titan.dsl.ScheduledJob;

                class ScheduledJobDemo {
                    @ScheduledJob(cron = "15 4 * * *", name = "nightly_refresh")
                    static void refreshNightly() {
                    }
                }
                """);

        List<TranspilationPipeline.GeneratedSql> generated = new TranspilationPipeline().transpile(
                List.of(source),
                List.of(),
                List.of("postgresql", "mysql"),
                List.of("app"),
                true);

        String postgresSql = generated.stream()
                .filter(sql -> sql.target().equals("postgresql"))
                .findFirst()
                .orElseThrow()
                .sql();
        String mysqlSql = generated.stream()
                .filter(sql -> sql.target().equals("mysql"))
                .findFirst()
                .orElseThrow()
                .sql();

        assertTrue(postgresSql.contains("PERFORM cron.schedule("));
        assertTrue(postgresSql.contains("IF EXISTS (SELECT 1 FROM pg_extension WHERE extname = 'pg_cron') THEN"));
        assertTrue(postgresSql.contains("nightly_refresh"));
        assertTrue(postgresSql.contains("15 4 * * *"));
        assertTrue(postgresSql.contains("CALL \"app\".\"refresh_nightly\"()"));
        assertTrue(mysqlSql.contains("CREATE EVENT `nightly_refresh`"));
        assertTrue(mysqlSql.contains("DO CALL `app`.`refresh_nightly`();"));
    }

    @Test
    void emitsEnumLookupArtifactsBeforeRoutineArtifacts() throws Exception {
        Path source = tempDir.resolve("EnumLookupDemo.java");
        Files.writeString(source, """
                import titan.dsl.StoredProcedure;

                class EnumLookupDemo {
                    enum PlanTier {
                        FREE(0),
                        PRO(100);

                        final int weight;

                        PlanTier(int weight) {
                            this.weight = weight;
                        }
                    }

                    @StoredProcedure
                    static void sync() {
                    }
                }
                """);

        List<TranspilationPipeline.GeneratedSql> generated = new TranspilationPipeline().transpile(
                List.of(source),
                List.of(),
                List.of("postgresql", "mysql"),
                List.of("app"),
                true);

        assertEquals(4, generated.size());

        TranspilationPipeline.GeneratedSql firstPostgres = generated.stream()
                .filter(sql -> sql.target().equals("postgresql"))
                .findFirst()
                .orElseThrow();
        assertEquals("PlanTier", firstPostgres.methodName());
        assertTrue(firstPostgres.sql().contains("CREATE TABLE IF NOT EXISTS \"app\".\"__enum_enum_lookup_demo_plan_tier\""));
        assertTrue(firstPostgres.sql().contains("\"ordinal\" INTEGER PRIMARY KEY"));
        assertTrue(firstPostgres.sql().contains("\"name\" TEXT UNIQUE NOT NULL"));
        assertTrue(firstPostgres.sql().contains("ON CONFLICT (\"ordinal\") DO UPDATE SET \"name\" = EXCLUDED.\"name\", \"weight\" = EXCLUDED.\"weight\""));
        assertTrue(firstPostgres.sql().contains("CREATE OR REPLACE FUNCTION \"app\".\"__enum_enum_lookup_demo_plan_tier__weight\"(p_enum_key TEXT)"));
        assertTrue(firstPostgres.sql().contains("WHERE \"name\" = p_enum_key"));
        assertTrue(firstPostgres.sql().contains("RETURNS INTEGER"));

        TranspilationPipeline.GeneratedSql firstMySql = generated.stream()
                .filter(sql -> sql.target().equals("mysql"))
                .findFirst()
                .orElseThrow();
        assertEquals("PlanTier", firstMySql.methodName());
        assertTrue(firstMySql.sql().contains("CREATE TABLE IF NOT EXISTS `app`.`__enum_enum_lookup_demo_plan_tier`"));
        assertTrue(firstMySql.sql().contains("`ordinal` INT PRIMARY KEY"));
        assertTrue(firstMySql.sql().contains("UNIQUE KEY `uq_enum_name` (`name`)"));
        assertTrue(firstMySql.sql().contains("ON DUPLICATE KEY UPDATE `name` = VALUES(`name`), `weight` = VALUES(`weight`)"));
        assertTrue(firstMySql.sql().contains("CREATE FUNCTION `app`.`__enum_enum_lookup_demo_plan_tier__weight`(p_enum_key VARCHAR(191)) RETURNS INT"));
        assertTrue(firstMySql.sql().contains("WHERE `name` = p_enum_key LIMIT 1"));
    }

    @Test
    void emitsEnumHelperCallsInsideGeneratedRoutineSql() throws Exception {
        Path source = tempDir.resolve("EnumHelperCallDemo.java");
        Files.writeString(source, """
                import titan.dsl.StoredProcedure;

                class EnumHelperCallDemo {
                    enum PlanTier {
                        FREE(0), PRO(100);
                        final int weight;
                        PlanTier(int weight) { this.weight = weight; }
                        int getWeight() { return weight; }
                    }

                    @StoredProcedure
                    static void sync(PlanTier tier) {
                        int current = tier.getWeight();
                    }
                }
                """);

        List<TranspilationPipeline.GeneratedSql> generated = new TranspilationPipeline().transpile(
                List.of(source),
                List.of(),
                List.of("postgresql", "mysql"),
                List.of("app"),
                true);

        String postgresEnumSql = generated.stream()
                .filter(sql -> sql.target().equals("postgresql"))
                .filter(sql -> sql.methodName().equals("PlanTier"))
                .findFirst()
                .orElseThrow()
                .sql();
        String mysqlEnumSql = generated.stream()
                .filter(sql -> sql.target().equals("mysql"))
                .filter(sql -> sql.methodName().equals("PlanTier"))
                .findFirst()
                .orElseThrow()
                .sql();
        String postgresRoutineSql = generated.stream()
                .filter(sql -> sql.target().equals("postgresql"))
                .filter(sql -> sql.methodName().equals("sync"))
                .findFirst()
                .orElseThrow()
                .sql();
        String mysqlRoutineSql = generated.stream()
                .filter(sql -> sql.target().equals("mysql"))
                .filter(sql -> sql.methodName().equals("sync"))
                .findFirst()
                .orElseThrow()
                .sql();

        assertTrue(postgresEnumSql.contains("CREATE OR REPLACE FUNCTION \"app\".\"__enum_enum_helper_call_demo_plan_tier__get_weight\"(p_enum_key TEXT)"));
        assertTrue(mysqlEnumSql.contains("CREATE FUNCTION `app`.`__enum_enum_helper_call_demo_plan_tier__get_weight`(p_enum_key VARCHAR(191)) RETURNS INT"));
        assertTrue(postgresRoutineSql.contains("__enum_enum_helper_call_demo_plan_tier__get_weight(p_tier)"));
        assertTrue(mysqlRoutineSql.contains("__enum_enum_helper_call_demo_plan_tier__get_weight(p_tier)"));
    }

    @Test
    void rejectsSharedCodeDefinedViewWhenSchemaViewAlreadyExists() throws Exception {
        Path source = tempDir.resolve("SharedViewConflictDemo.java");
        Files.writeString(source, """
                import titan.dsl.ViewDefinition;

                class SharedViewConflictDemo {
                    @ViewDefinition(name = "high_value_accounts", shared = true)
                    static final Object HIGH_VALUE = null;
                }
                """);

        try {
            new TranspilationPipeline().transpile(
                    List.of(source),
                    List.of(),
                    List.of("postgresql"),
                    List.of("app"),
                    true,
                    List.of("app.high_value_accounts"));
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("defined both in the database schema and in code"));
            assertTrue(e.getMessage().contains("Schema-defined views take precedence"));
            return;
        }

        throw new AssertionError("Expected schema/code view conflict to throw.");
    }

    @Test
    void emitsSharedCodeDefinedViewBeforeRoutineArtifacts() throws Exception {
        Path source = tempDir.resolve("SharedViewEmissionDemo.java");
        Files.writeString(source, """
                import titan.dsl.StoredProcedure;
                import titan.dsl.ViewDefinition;

                class SharedViewEmissionDemo {
                    @ViewDefinition(name = "high_value_accounts", shared = true)
                    static final String HIGH_VALUE = "SELECT id, email FROM accounts WHERE active = true";

                    @StoredProcedure
                    static void refresh() {
                    }
                }
                """);

        List<TranspilationPipeline.GeneratedSql> generated = new TranspilationPipeline().transpile(
                List.of(source),
                List.of(),
                List.of("postgresql", "mysql"),
                List.of("app"),
                true);

        assertEquals(4, generated.size());

        TranspilationPipeline.GeneratedSql firstPostgres = generated.stream()
                .filter(sql -> sql.target().equals("postgresql"))
                .findFirst()
                .orElseThrow();
        assertTrue(firstPostgres.methodName().equals("HIGH_VALUE"));
        assertTrue(firstPostgres.sql().contains("CREATE OR REPLACE VIEW \"app\".\"high_value_accounts\" WITH (security_invoker = true) AS"));

        TranspilationPipeline.GeneratedSql firstMySql = generated.stream()
                .filter(sql -> sql.target().equals("mysql"))
                .findFirst()
                .orElseThrow();
        assertTrue(firstMySql.methodName().equals("HIGH_VALUE"));
        assertTrue(firstMySql.sql().contains("DROP VIEW IF EXISTS `app`.`high_value_accounts`;"));
        assertTrue(firstMySql.sql().contains("CREATE SQL SECURITY INVOKER VIEW `app`.`high_value_accounts` AS"));
    }

    @Test
    void rejectsSharedViewWithoutSqlStringInitializer() throws Exception {
        Path source = tempDir.resolve("SharedViewMissingSqlDemo.java");
        Files.writeString(source, """
                import titan.dsl.ViewDefinition;

                class SharedViewMissingSqlDemo {
                    @ViewDefinition(name = "high_value_accounts", shared = true)
                    static final Object HIGH_VALUE = null;
                }
                """);

        assertThrows(IllegalArgumentException.class, () ->
                new TranspilationPipeline().transpile(
                        List.of(source),
                        List.of(),
                        List.of("postgresql"),
                        List.of("app"),
                        true));
    }

    @Test
    void ordersSharedCodeDefinedViewsByInterViewDependencies() throws Exception {
        Path source = tempDir.resolve("SharedViewDependencyOrderDemo.java");
        Files.writeString(source, """
                import titan.dsl.StoredProcedure;
                import titan.dsl.ViewDefinition;

                class SharedViewDependencyOrderDemo {
                    @ViewDefinition(name = "high_value_accounts", shared = true)
                    static final String HIGH_VALUE = "SELECT id FROM accounts WHERE active = true";

                    @ViewDefinition(name = "high_value_account_ids", shared = true)
                    static final String HIGH_VALUE_IDS = "SELECT id FROM high_value_accounts";

                    @StoredProcedure
                    static void refresh() {
                    }
                }
                """);

        List<TranspilationPipeline.GeneratedSql> generated = new TranspilationPipeline().transpile(
                List.of(source),
                List.of(),
                List.of("postgresql"),
                List.of("app"),
                true);

        List<TranspilationPipeline.GeneratedSql> postgresArtifacts = generated.stream()
                .filter(sql -> sql.target().equals("postgresql"))
                .toList();

        assertEquals("HIGH_VALUE", postgresArtifacts.get(0).methodName());
        assertEquals("HIGH_VALUE_IDS", postgresArtifacts.get(1).methodName());
        assertTrue(postgresArtifacts.get(1).sql().contains("FROM high_value_accounts"));
    }

    @Test
    void rejectsCyclesInSharedCodeDefinedViewDependencies() throws Exception {
        Path source = tempDir.resolve("SharedViewCycleDemo.java");
        Files.writeString(source, """
                import titan.dsl.ViewDefinition;

                class SharedViewCycleDemo {
                    @ViewDefinition(name = "a_view", shared = true)
                    static final String A = "SELECT id FROM b_view";

                    @ViewDefinition(name = "b_view", shared = true)
                    static final String B = "SELECT id FROM a_view";
                }
                """);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                new TranspilationPipeline().transpile(
                        List.of(source),
                        List.of(),
                        List.of("postgresql"),
                        List.of("app"),
                        true));
        assertTrue(error.getMessage().contains("Cycle detected in shared @ViewDefinition dependencies"));
    }

    @Test
    void rewritesOverloadedExpressionCallSitesForMySql() throws Exception {
        Path source = tempDir.resolve("OverloadExpressionCallDemo.java");
        Files.writeString(source, """
                import titan.dsl.StoredFunction;

                class OverloadExpressionCallDemo {
                    @StoredFunction
                    static int root() {
                        return calc(42) + calc("ok");
                    }

                    @StoredFunction
                    static int calc(int value) {
                        return value;
                    }

                    @StoredFunction
                    static int calc(String value) {
                        return 1;
                    }
                }
                """);

        List<TranspilationPipeline.GeneratedSql> generated = new TranspilationPipeline().transpile(
                List.of(source),
                List.of(),
                List.of("mysql"),
                List.of("app"),
                true);

        String rootSql = generated.stream()
                .filter(sql -> sql.target().equals("mysql") && sql.methodName().equals("root"))
                .findFirst()
                .orElseThrow()
                .sql();

        assertTrue(rootSql.contains("calc__int(42)"));
        assertTrue(rootSql.contains("calc__java_lang_string('ok')"));
    }

    @Test
    void emitsStoredFunctionSqlWithDiscoveredReturnType() throws Exception {
        Path source = tempDir.resolve("FunctionReturnTypeDemo.java");
        Files.writeString(source, """
                import titan.dsl.StoredFunction;

                class FunctionReturnTypeDemo {
                    @StoredFunction
                    static int countActive() {
                        return 1;
                    }
                }
                """);

        List<TranspilationPipeline.GeneratedSql> generated = new TranspilationPipeline().transpile(
                List.of(source),
                List.of(),
                List.of("postgresql", "mysql"),
                List.of("app"),
                true);

        String postgresSql = generated.stream()
                .filter(sql -> sql.target().equals("postgresql") && sql.methodName().equals("countActive"))
                .findFirst()
                .orElseThrow()
                .sql();
        assertTrue(postgresSql.contains("RETURNS INTEGER"));

        String mySqlSql = generated.stream()
                .filter(sql -> sql.target().equals("mysql") && sql.methodName().equals("countActive"))
                .findFirst()
                .orElseThrow()
                .sql();
        assertTrue(mySqlSql.contains("RETURNS INT"));
    }

    @Test
    void transpilesJavaTimeIntoProcedureSql() throws Exception {
        Path source = tempDir.resolve("JavaTimePipelineDemo.java");
        Files.writeString(source, """
                import java.time.Instant;
                import java.time.LocalDate;
                import java.time.LocalDateTime;
                import java.time.LocalTime;
                import java.time.ZonedDateTime;
                import titan.dsl.StoredProcedure;

                class JavaTimePipelineDemo {
                    @StoredProcedure
                    static void run() {
                        LocalDate today = LocalDate.now();
                        LocalTime nowTime = LocalTime.now();
                        LocalDate tomorrow = today.plusDays(1);
                        LocalDate yesterday = today.minusDays(1);
                        LocalDateTime now = LocalDateTime.now();
                        LocalDateTime later = now.plusHours(3);
                        LocalDateTime earlier = now.minusHours(2);
                        LocalDate justDate = now.toLocalDate();
                        LocalTime justTime = now.toLocalTime();
                        Instant instantNow = Instant.now();
                        ZonedDateTime zonedNow = ZonedDateTime.now();
                        Instant fromZoned = zonedNow.toInstant();
                    }
                }
                """);

        List<TranspilationPipeline.GeneratedSql> generated = new TranspilationPipeline().transpile(
                List.of(source),
                List.of(),
                List.of("postgresql", "mysql"),
                List.of("app"),
                true);

        String postgresSql = generated.stream()
                .filter(sql -> sql.target().equals("postgresql") && sql.methodName().equals("run"))
                .findFirst()
                .orElseThrow()
                .sql();
        String mysqlSql = generated.stream()
                .filter(sql -> sql.target().equals("mysql") && sql.methodName().equals("run"))
                .findFirst()
                .orElseThrow()
                .sql();

        assertTrue(postgresSql.contains("CREATE OR REPLACE PROCEDURE \"app\".\"run\"()"));
        assertTrue(postgresSql.contains("v_today DATE;"));
        assertTrue(postgresSql.contains("v_today := CURRENT_DATE;"));
        assertTrue(postgresSql.contains("v_now_time TIME;"));
        assertTrue(postgresSql.contains("v_now_time := LOCALTIME;"));
        assertTrue(postgresSql.contains("v_tomorrow := (v_today + (1 * INTERVAL '1 day'));"));
        assertTrue(postgresSql.contains("v_yesterday := (v_today - (1 * INTERVAL '1 day'));"));
        assertTrue(postgresSql.contains("v_now TIMESTAMP;"));
        assertTrue(postgresSql.contains("v_now := CURRENT_TIMESTAMP;"));
        assertTrue(postgresSql.contains("v_later := (v_now + (3 * INTERVAL '1 hour'));"));
        assertTrue(postgresSql.contains("v_earlier := (v_now - (2 * INTERVAL '1 hour'));"));
        assertTrue(postgresSql.contains("v_just_date := DATE(v_now);"));
        assertTrue(postgresSql.contains("v_just_time := CAST(v_now AS TIME);"));
        assertTrue(postgresSql.contains("v_instant_now := CURRENT_TIMESTAMP;"));
        assertTrue(postgresSql.contains("v_zoned_now := CURRENT_TIMESTAMP;"));
        assertTrue(postgresSql.contains("v_from_zoned := CAST(v_zoned_now AS TIMESTAMPTZ);"));

        assertTrue(mysqlSql.contains("CREATE PROCEDURE `app`.`run`()"));
        assertTrue(mysqlSql.contains("SET time_zone = '+00:00';"));
        assertTrue(mysqlSql.contains("DECLARE v_today DATE;"));
        assertTrue(mysqlSql.contains("SET v_today = CURRENT_DATE;"));
        assertTrue(mysqlSql.contains("DECLARE v_now_time TIME;"));
        assertTrue(mysqlSql.contains("SET v_now_time = CURRENT_TIME;"));
        assertTrue(mysqlSql.contains("SET v_tomorrow = DATE_ADD(v_today, INTERVAL 1 DAY);"));
        assertTrue(mysqlSql.contains("SET v_yesterday = DATE_SUB(v_today, INTERVAL 1 DAY);"));
        assertTrue(mysqlSql.contains("DECLARE v_now DATETIME(6);"));
        assertTrue(mysqlSql.contains("SET v_now = CURRENT_TIMESTAMP;"));
        assertTrue(mysqlSql.contains("SET v_later = DATE_ADD(v_now, INTERVAL 3 HOUR);"));
        assertTrue(mysqlSql.contains("SET v_earlier = DATE_SUB(v_now, INTERVAL 2 HOUR);"));
        assertTrue(mysqlSql.contains("SET v_just_date = DATE(v_now);"));
        assertTrue(mysqlSql.contains("SET v_just_time = TIME(v_now);"));
        assertTrue(mysqlSql.contains("SET v_instant_now = UTC_TIMESTAMP();"));
        assertTrue(mysqlSql.contains("SET v_zoned_now = CONVERT_TZ(UTC_TIMESTAMP(), '+00:00', @@session.time_zone);"));
        assertTrue(mysqlSql.contains("SET v_from_zoned = CONVERT_TZ(v_zoned_now, @@session.time_zone, '+00:00');"));
    }

    @Test
    void transpilesTemporalAmountsIntoProcedureSql() throws Exception {
        Path source = tempDir.resolve("TemporalAmountPipelineDemo.java");
        Files.writeString(source, """
                import java.time.Duration;
                import java.time.LocalDateTime;
                import java.time.Period;
                import titan.dsl.StoredProcedure;

                class TemporalAmountPipelineDemo {
                    @StoredProcedure
                    static void run() {
                        Duration timeout = Duration.ofSeconds(90);
                        Duration cooldown = Duration.ofHours(2);
                        Period grace = Period.ofDays(2);
                        Period nextMonth = Period.ofMonths(1);
                        LocalDateTime now = LocalDateTime.now();
                        LocalDateTime plusTimeout = now.plus(timeout);
                        LocalDateTime minusCooldown = now.minus(cooldown);
                        LocalDateTime minusGrace = now.minus(grace);
                        LocalDateTime plusMonth = now.plus(nextMonth);
                    }
                }
                """);

        List<TranspilationPipeline.GeneratedSql> generated = new TranspilationPipeline().transpile(
                List.of(source),
                List.of(),
                List.of("postgresql", "mysql"),
                List.of("app"),
                true);

        String postgresSql = generated.stream()
                .filter(sql -> sql.target().equals("postgresql") && sql.methodName().equals("run"))
                .findFirst()
                .orElseThrow()
                .sql();
        String mysqlSql = generated.stream()
                .filter(sql -> sql.target().equals("mysql") && sql.methodName().equals("run"))
                .findFirst()
                .orElseThrow()
                .sql();

        assertTrue(postgresSql.contains("v_timeout := (90 * INTERVAL '1 second');"));
        assertTrue(postgresSql.contains("v_cooldown := (2 * INTERVAL '1 hour');"));
        assertTrue(postgresSql.contains("v_grace := (2 * INTERVAL '1 day');"));
        assertTrue(postgresSql.contains("v_next_month := (1 * INTERVAL '1 month');"));
        assertTrue(postgresSql.contains("v_plus_timeout := (v_now + v_timeout);"));
        assertTrue(postgresSql.contains("v_minus_cooldown := (v_now - v_cooldown);"));
        assertTrue(postgresSql.contains("v_minus_grace := (v_now - v_grace);"));
        assertTrue(postgresSql.contains("v_plus_month := (v_now + v_next_month);"));

        assertTrue(mysqlSql.contains("DECLARE v_timeout BIGINT;"));
        assertTrue(mysqlSql.contains("SET v_timeout = 90;"));
        assertTrue(mysqlSql.contains("DECLARE v_cooldown BIGINT;"));
        assertTrue(mysqlSql.contains("SET v_cooldown = (2 * 3600);"));
        assertTrue(mysqlSql.contains("DECLARE v_grace BIGINT DEFAULT 2;"));
        assertTrue(mysqlSql.contains("SET v_grace = 2;"));
        assertTrue(mysqlSql.contains("DECLARE v_next_month BIGINT DEFAULT 1;"));
        assertTrue(mysqlSql.contains("SET v_next_month = 1;"));
        assertTrue(mysqlSql.contains("DECLARE v_plus_timeout DATETIME(6);"));
        assertTrue(mysqlSql.contains("SET v_plus_timeout = DATE_ADD(v_now, INTERVAL v_timeout SECOND);"));
        assertTrue(mysqlSql.contains("DECLARE v_minus_cooldown DATETIME(6);"));
        assertTrue(mysqlSql.contains("SET v_minus_cooldown = DATE_SUB(v_now, INTERVAL v_cooldown SECOND);"));
        assertTrue(mysqlSql.contains("DECLARE v_minus_grace DATETIME(6);"));
        assertTrue(mysqlSql.contains("SET v_minus_grace = DATE_SUB(v_now, INTERVAL v_grace DAY);"));
        assertTrue(mysqlSql.contains("DECLARE v_plus_month DATETIME(6);"));
        assertTrue(mysqlSql.contains("SET v_plus_month = DATE_ADD(v_now, INTERVAL v_next_month MONTH);"));
    }

    @Test
    void transpilesTemporalZeroAndAdditionalUnitsIntoProcedureSql() throws Exception {
        Path source = tempDir.resolve("TemporalAmountAdditionalUnitsDemo.java");
        Files.writeString(source, """
                import java.time.Duration;
                import java.time.LocalDateTime;
                import java.time.Period;
                import titan.dsl.StoredProcedure;

                class TemporalAmountAdditionalUnitsDemo {
                    @StoredProcedure
                    static void run() {
                        Duration idle = Duration.ofSeconds(0);
                        Duration retry = Duration.ofMinutes(5);
                        Period sameDay = Period.ofDays(0);
                        Period nextYear = Period.ofYears(1);
                        LocalDateTime now = LocalDateTime.now();
                        LocalDateTime stillNow = now.plus(idle);
                        LocalDateTime beforeRetry = now.minus(retry);
                        LocalDateTime sameMoment = now.plus(sameDay);
                        LocalDateTime nextYearMoment = now.plus(nextYear);
                    }
                }
                """);

        List<TranspilationPipeline.GeneratedSql> generated = new TranspilationPipeline().transpile(
                List.of(source),
                List.of(),
                List.of("postgresql", "mysql"),
                List.of("app"),
                true);

        String postgresSql = generated.stream()
                .filter(sql -> sql.target().equals("postgresql") && sql.methodName().equals("run"))
                .findFirst()
                .orElseThrow()
                .sql();
        String mysqlSql = generated.stream()
                .filter(sql -> sql.target().equals("mysql") && sql.methodName().equals("run"))
                .findFirst()
                .orElseThrow()
                .sql();

        assertTrue(postgresSql.contains("v_idle := (0 * INTERVAL '1 second');"));
        assertTrue(postgresSql.contains("v_retry := (5 * INTERVAL '1 minute');"));
        assertTrue(postgresSql.contains("v_same_day := (0 * INTERVAL '1 day');"));
        assertTrue(postgresSql.contains("v_next_year := (1 * INTERVAL '1 year');"));
        assertTrue(postgresSql.contains("v_still_now := (v_now + v_idle);"));
        assertTrue(postgresSql.contains("v_before_retry := (v_now - v_retry);"));
        assertTrue(postgresSql.contains("v_same_moment := (v_now + v_same_day);"));
        assertTrue(postgresSql.contains("v_next_year_moment := (v_now + v_next_year);"));

        assertTrue(mysqlSql.contains("DECLARE v_idle BIGINT;"));
        assertTrue(mysqlSql.contains("SET v_idle = 0;"));
        assertTrue(mysqlSql.contains("DECLARE v_retry BIGINT;"));
        assertTrue(mysqlSql.contains("SET v_retry = (5 * 60);"));
        assertTrue(mysqlSql.contains("DECLARE v_same_day BIGINT DEFAULT 0;"));
        assertTrue(mysqlSql.contains("SET v_same_day = 0;"));
        assertTrue(mysqlSql.contains("DECLARE v_next_year BIGINT DEFAULT 1;"));
        assertTrue(mysqlSql.contains("SET v_next_year = 1;"));
        assertTrue(mysqlSql.contains("DECLARE v_still_now DATETIME(6);"));
        assertTrue(mysqlSql.contains("SET v_still_now = DATE_ADD(v_now, INTERVAL v_idle SECOND);"));
        assertTrue(mysqlSql.contains("DECLARE v_before_retry DATETIME(6);"));
        assertTrue(mysqlSql.contains("SET v_before_retry = DATE_SUB(v_now, INTERVAL v_retry SECOND);"));
        assertTrue(mysqlSql.contains("DECLARE v_same_moment DATETIME(6);"));
        assertTrue(mysqlSql.contains("SET v_same_moment = DATE_ADD(v_now, INTERVAL v_same_day DAY);"));
        assertTrue(mysqlSql.contains("DECLARE v_next_year_moment DATETIME(6);"));
        assertTrue(mysqlSql.contains("SET v_next_year_moment = DATE_ADD(v_now, INTERVAL v_next_year YEAR);"));
    }

    @Test
    void emitsTemporalRoutineParameterAndReturnTypes() throws Exception {
        Path source = tempDir.resolve("TemporalRoutineTypesDemo.java");
        Files.writeString(source, """
                import java.time.Instant;
                import java.time.LocalDate;
                import java.time.LocalDateTime;
                import java.time.LocalTime;
                import titan.dsl.StoredFunction;

                class TemporalRoutineTypesDemo {
                    @StoredFunction
                    static LocalDate nextDay(LocalDate input) {
                        return input.plusDays(1);
                    }

                    @StoredFunction
                    static LocalTime currentTimeValue() {
                        return LocalTime.now();
                    }

                    @StoredFunction
                    static LocalDateTime currentTimestampValue() {
                        return LocalDateTime.now();
                    }

                    @StoredFunction
                    static Instant currentInstantValue() {
                        return Instant.now();
                    }
                }
                """);

        List<TranspilationPipeline.GeneratedSql> generated = new TranspilationPipeline().transpile(
                List.of(source),
                List.of(),
                List.of("postgresql", "mysql"),
                List.of("app"),
                true);

        String postgresNextDay = generated.stream()
                .filter(sql -> sql.target().equals("postgresql") && sql.methodName().equals("nextDay"))
                .findFirst()
                .orElseThrow()
                .sql();
        String mysqlNextDay = generated.stream()
                .filter(sql -> sql.target().equals("mysql") && sql.methodName().equals("nextDay"))
                .findFirst()
                .orElseThrow()
                .sql();
        String postgresCurrentTime = generated.stream()
                .filter(sql -> sql.target().equals("postgresql") && sql.methodName().equals("currentTimeValue"))
                .findFirst()
                .orElseThrow()
                .sql();
        String mysqlCurrentTime = generated.stream()
                .filter(sql -> sql.target().equals("mysql") && sql.methodName().equals("currentTimeValue"))
                .findFirst()
                .orElseThrow()
                .sql();
        String postgresCurrentTimestamp = generated.stream()
                .filter(sql -> sql.target().equals("postgresql") && sql.methodName().equals("currentTimestampValue"))
                .findFirst()
                .orElseThrow()
                .sql();
        String mysqlCurrentTimestamp = generated.stream()
                .filter(sql -> sql.target().equals("mysql") && sql.methodName().equals("currentTimestampValue"))
                .findFirst()
                .orElseThrow()
                .sql();
        String postgresCurrentInstant = generated.stream()
                .filter(sql -> sql.target().equals("postgresql") && sql.methodName().equals("currentInstantValue"))
                .findFirst()
                .orElseThrow()
                .sql();
        String mysqlCurrentInstant = generated.stream()
                .filter(sql -> sql.target().equals("mysql") && sql.methodName().equals("currentInstantValue"))
                .findFirst()
                .orElseThrow()
                .sql();

        assertTrue(postgresNextDay.contains("CREATE OR REPLACE FUNCTION \"app\".\"next_day\"(p_input DATE)"));
        assertTrue(postgresNextDay.contains("RETURNS DATE"));
        assertTrue(postgresNextDay.contains("RETURN (p_input + (1 * INTERVAL '1 day'));"));

        assertTrue(mysqlNextDay.contains("CREATE FUNCTION `app`.`next_day`(p_input DATE)"));
        assertTrue(mysqlNextDay.contains("RETURNS DATE"));
        assertTrue(mysqlNextDay.contains("SET __titan_return_value = DATE_ADD(p_input, INTERVAL 1 DAY);"));

        assertTrue(postgresCurrentTime.contains("RETURNS TIME"));
        assertTrue(postgresCurrentTime.contains("RETURN LOCALTIME;"));
        assertTrue(mysqlCurrentTime.contains("RETURNS TIME"));
        assertTrue(mysqlCurrentTime.contains("SET __titan_return_value = CURRENT_TIME;"));

        assertTrue(postgresCurrentTimestamp.contains("RETURNS TIMESTAMP"));
        assertTrue(postgresCurrentTimestamp.contains("RETURN CURRENT_TIMESTAMP;"));
        assertTrue(mysqlCurrentTimestamp.contains("RETURNS DATETIME(6)"));
        assertTrue(mysqlCurrentTimestamp.contains("SET __titan_return_value = CURRENT_TIMESTAMP;"));

        assertTrue(postgresCurrentInstant.contains("RETURNS TIMESTAMPTZ"));
        assertTrue(postgresCurrentInstant.contains("RETURN CURRENT_TIMESTAMP;"));
        assertTrue(mysqlCurrentInstant.contains("RETURNS TIMESTAMP"));
        assertTrue(mysqlCurrentInstant.contains("SET __titan_return_value = UTC_TIMESTAMP();"));
    }

    @Test
    void emitsTemporalConversionAndZonedReturnFunctions() throws Exception {
        Path source = tempDir.resolve("TemporalConversionRoutineDemo.java");
        Files.writeString(source, """
                import java.time.Instant;
                import java.time.LocalDate;
                import java.time.LocalDateTime;
                import java.time.LocalTime;
                import java.time.ZonedDateTime;
                import titan.dsl.StoredFunction;

                class TemporalConversionRoutineDemo {
                    @StoredFunction
                    static LocalDate extractDate(LocalDateTime input) {
                        return input.toLocalDate();
                    }

                    @StoredFunction
                    static LocalTime extractTime(LocalDateTime input) {
                        return input.toLocalTime();
                    }

                    @StoredFunction
                    static Instant extractInstant(ZonedDateTime input) {
                        return input.toInstant();
                    }

                    @StoredFunction
                    static ZonedDateTime currentZonedValue() {
                        return ZonedDateTime.now();
                    }
                }
                """);

        List<TranspilationPipeline.GeneratedSql> generated = new TranspilationPipeline().transpile(
                List.of(source),
                List.of(),
                List.of("postgresql", "mysql"),
                List.of("app"),
                true);

        String postgresExtractDate = generated.stream()
                .filter(sql -> sql.target().equals("postgresql") && sql.methodName().equals("extractDate"))
                .findFirst()
                .orElseThrow()
                .sql();
        String mysqlExtractDate = generated.stream()
                .filter(sql -> sql.target().equals("mysql") && sql.methodName().equals("extractDate"))
                .findFirst()
                .orElseThrow()
                .sql();
        String postgresExtractTime = generated.stream()
                .filter(sql -> sql.target().equals("postgresql") && sql.methodName().equals("extractTime"))
                .findFirst()
                .orElseThrow()
                .sql();
        String mysqlExtractTime = generated.stream()
                .filter(sql -> sql.target().equals("mysql") && sql.methodName().equals("extractTime"))
                .findFirst()
                .orElseThrow()
                .sql();
        String postgresExtractInstant = generated.stream()
                .filter(sql -> sql.target().equals("postgresql") && sql.methodName().equals("extractInstant"))
                .findFirst()
                .orElseThrow()
                .sql();
        String mysqlExtractInstant = generated.stream()
                .filter(sql -> sql.target().equals("mysql") && sql.methodName().equals("extractInstant"))
                .findFirst()
                .orElseThrow()
                .sql();
        String postgresCurrentZoned = generated.stream()
                .filter(sql -> sql.target().equals("postgresql") && sql.methodName().equals("currentZonedValue"))
                .findFirst()
                .orElseThrow()
                .sql();
        String mysqlCurrentZoned = generated.stream()
                .filter(sql -> sql.target().equals("mysql") && sql.methodName().equals("currentZonedValue"))
                .findFirst()
                .orElseThrow()
                .sql();

        assertTrue(postgresExtractDate.contains("CREATE OR REPLACE FUNCTION \"app\".\"extract_date\"(p_input TIMESTAMP)"));
        assertTrue(postgresExtractDate.contains("RETURNS DATE"));
        assertTrue(postgresExtractDate.contains("RETURN DATE(p_input);"));
        assertTrue(mysqlExtractDate.contains("CREATE FUNCTION `app`.`extract_date`(p_input DATETIME(6))"));
        assertTrue(mysqlExtractDate.contains("RETURNS DATE"));
        assertTrue(mysqlExtractDate.contains("SET __titan_return_value = DATE(p_input);"));

        assertTrue(postgresExtractTime.contains("RETURNS TIME"));
        assertTrue(postgresExtractTime.contains("RETURN CAST(p_input AS TIME);"));
        assertTrue(mysqlExtractTime.contains("RETURNS TIME"));
        assertTrue(mysqlExtractTime.contains("SET __titan_return_value = TIME(p_input);"));

        assertTrue(postgresExtractInstant.contains("CREATE OR REPLACE FUNCTION \"app\".\"extract_instant\"(p_input TIMESTAMPTZ)"));
        assertTrue(postgresExtractInstant.contains("RETURNS TIMESTAMPTZ"));
        assertTrue(postgresExtractInstant.contains("RETURN CAST(p_input AS TIMESTAMPTZ);"));
        assertTrue(mysqlExtractInstant.contains("CREATE FUNCTION `app`.`extract_instant`(p_input TIMESTAMP(6))"));
        assertTrue(mysqlExtractInstant.contains("RETURNS TIMESTAMP(6)"));
        assertTrue(mysqlExtractInstant.contains("SET __titan_return_value = CONVERT_TZ(p_input, @@session.time_zone, '+00:00');"));

        assertTrue(postgresCurrentZoned.contains("RETURNS TIMESTAMPTZ"));
        assertTrue(postgresCurrentZoned.contains("RETURN CURRENT_TIMESTAMP;"));
        assertTrue(mysqlCurrentZoned.contains("RETURNS TIMESTAMP(6)"));
        assertTrue(mysqlCurrentZoned.contains("SET __titan_return_value = CONVERT_TZ(UTC_TIMESTAMP(), '+00:00', @@session.time_zone);"));
    }

    @Test
    void emitsPostgresIntervalTypedRoutineBoundaries() throws Exception {
        Path source = tempDir.resolve("PostgresIntervalRoutineDemo.java");
        Files.writeString(source, """
                import java.time.Duration;
                import java.time.LocalDateTime;
                import java.time.Period;
                import titan.dsl.StoredFunction;

                class PostgresIntervalRoutineDemo {
                    @StoredFunction
                    static Duration defaultTimeout() {
                        return Duration.ofMinutes(5);
                    }

                    @StoredFunction
                    static LocalDateTime applyTimeout(LocalDateTime input, Duration timeout) {
                        return input.plus(timeout);
                    }

                    @StoredFunction
                    static LocalDateTime applyGrace(LocalDateTime input, Period grace) {
                        return input.plus(grace);
                    }
                }
                """);

        List<TranspilationPipeline.GeneratedSql> generated = new TranspilationPipeline().transpile(
                List.of(source),
                List.of(),
                List.of("postgresql"),
                List.of("app"),
                true);

        String postgresDefaultTimeout = generated.stream()
                .filter(sql -> sql.target().equals("postgresql") && sql.methodName().equals("defaultTimeout"))
                .findFirst()
                .orElseThrow()
                .sql();
        String postgresApplyTimeout = generated.stream()
                .filter(sql -> sql.target().equals("postgresql") && sql.methodName().equals("applyTimeout"))
                .findFirst()
                .orElseThrow()
                .sql();
        String postgresApplyGrace = generated.stream()
                .filter(sql -> sql.target().equals("postgresql") && sql.methodName().equals("applyGrace"))
                .findFirst()
                .orElseThrow()
                .sql();

        assertTrue(postgresDefaultTimeout.contains("CREATE OR REPLACE FUNCTION \"app\".\"default_timeout\"()"));
        assertTrue(postgresDefaultTimeout.contains("RETURNS INTERVAL"));
        assertTrue(postgresDefaultTimeout.contains("RETURN (5 * INTERVAL '1 minute');"));

        assertTrue(postgresApplyTimeout.contains("CREATE OR REPLACE FUNCTION \"app\".\"apply_timeout\"(p_input TIMESTAMP, p_timeout INTERVAL)"));
        assertTrue(postgresApplyTimeout.contains("RETURNS TIMESTAMP"));
        assertTrue(postgresApplyTimeout.contains("RETURN (p_input + p_timeout);"));

        assertTrue(postgresApplyGrace.contains("CREATE OR REPLACE FUNCTION \"app\".\"apply_grace\"(p_input TIMESTAMP, p_grace INTERVAL)"));
        assertTrue(postgresApplyGrace.contains("RETURNS TIMESTAMP"));
        assertTrue(postgresApplyGrace.contains("RETURN (p_input + p_grace);"));
    }

    @Test
    void emitsMySqlDurationTypedRoutineBoundaries() throws Exception {
        Path source = tempDir.resolve("MySqlDurationRoutineDemo.java");
        Files.writeString(source, """
                import java.time.Duration;
                import java.time.LocalDateTime;
                import titan.dsl.StoredFunction;

                class MySqlDurationRoutineDemo {
                    @StoredFunction
                    static Duration defaultTimeout() {
                        return Duration.ofMinutes(5);
                    }

                    @StoredFunction
                    static LocalDateTime applyTimeout(LocalDateTime input, Duration timeout) {
                        return input.plus(timeout);
                    }
                }
                """);

        List<TranspilationPipeline.GeneratedSql> generated = new TranspilationPipeline().transpile(
                List.of(source),
                List.of(),
                List.of("mysql"),
                List.of("app"),
                true);

        String mysqlDefaultTimeout = generated.stream()
                .filter(sql -> sql.target().equals("mysql") && sql.methodName().equals("defaultTimeout"))
                .findFirst()
                .orElseThrow()
                .sql();
        String mysqlApplyTimeout = generated.stream()
                .filter(sql -> sql.target().equals("mysql") && sql.methodName().equals("applyTimeout"))
                .findFirst()
                .orElseThrow()
                .sql();

        assertTrue(mysqlDefaultTimeout.contains("CREATE FUNCTION `app`.`default_timeout`()"));
        assertTrue(mysqlDefaultTimeout.contains("RETURNS BIGINT"));
        assertTrue(mysqlDefaultTimeout.contains("SET __titan_return_value = (5 * 60);"));

        assertTrue(mysqlApplyTimeout.contains("CREATE FUNCTION `app`.`apply_timeout`(p_input DATETIME(6), p_timeout BIGINT)"));
        assertTrue(mysqlApplyTimeout.contains("RETURNS DATETIME(6)"));
        assertTrue(mysqlApplyTimeout.contains("SET __titan_return_value = DATE_ADD(p_input, INTERVAL p_timeout SECOND);"));
    }

    @Test
    void normalizesDerivedAndReassignedMySqlDurationLocals() throws Exception {
        Path source = tempDir.resolve("MySqlDerivedDurationRoutineDemo.java");
        Files.writeString(source, """
                import java.time.Duration;
                import java.time.LocalDateTime;
                import titan.dsl.StoredFunction;

                class MySqlDerivedDurationRoutineDemo {
                    @StoredFunction
                    static Duration copyTimeout() {
                        Duration timeout = Duration.ofMinutes(5);
                        Duration adjusted = timeout;
                        return adjusted;
                    }

                    @StoredFunction
                    static LocalDateTime applyTimeoutAfterReassign(LocalDateTime input, Duration timeout) {
                        Duration adjusted = Duration.ofMinutes(5);
                        adjusted = timeout;
                        return input.plus(adjusted);
                    }
                }
                """);

        List<TranspilationPipeline.GeneratedSql> generated = new TranspilationPipeline().transpile(
                List.of(source),
                List.of(),
                List.of("mysql"),
                List.of("app"),
                true);

        String mysqlCopyTimeout = generated.stream()
                .filter(sql -> sql.target().equals("mysql") && sql.methodName().equals("copyTimeout"))
                .findFirst()
                .orElseThrow()
                .sql();
        String mysqlApplyTimeout = generated.stream()
                .filter(sql -> sql.target().equals("mysql") && sql.methodName().equals("applyTimeoutAfterReassign"))
                .findFirst()
                .orElseThrow()
                .sql();

        assertTrue(mysqlCopyTimeout.contains("DECLARE v_timeout BIGINT;"));
        assertTrue(mysqlCopyTimeout.contains("SET v_timeout = (5 * 60);"));
        assertTrue(mysqlCopyTimeout.contains("DECLARE v_adjusted BIGINT;"));
        assertTrue(mysqlCopyTimeout.contains("SET v_adjusted = v_timeout;"));
        assertTrue(mysqlCopyTimeout.contains("SET __titan_return_value = v_adjusted;"));

        assertTrue(mysqlApplyTimeout.contains("DECLARE v_adjusted BIGINT;"));
        assertTrue(mysqlApplyTimeout.contains("SET v_adjusted = (5 * 60);"));
        assertTrue(mysqlApplyTimeout.contains("SET v_adjusted = p_timeout;"));
        assertTrue(mysqlApplyTimeout.contains("SET __titan_return_value = DATE_ADD(p_input, INTERVAL v_adjusted SECOND);"));
    }

    @Test
    void rejectsMySqlPeriodTypedRoutineParameters() throws Exception {
        Path source = tempDir.resolve("MySqlPeriodParameterRoutineDemo.java");
        Files.writeString(source, """
                import java.time.LocalDateTime;
                import java.time.Period;
                import titan.dsl.StoredFunction;

                class MySqlPeriodParameterRoutineDemo {
                    @StoredFunction
                    static LocalDateTime applyGrace(LocalDateTime input, Period grace) {
                        return input.plus(grace);
                    }
                }
                """);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                new TranspilationPipeline().transpile(
                        List.of(source),
                        List.of(),
                        List.of("mysql"),
                        List.of("app"),
                        true));

        assertTrue(error.getMessage().contains("cannot expose period-like parameter"));
    }

    @Test
    void rejectsMySqlPeriodTypedRoutineReturns() throws Exception {
        Path source = tempDir.resolve("MySqlPeriodReturnRoutineDemo.java");
        Files.writeString(source, """
                import java.time.Period;
                import titan.dsl.StoredFunction;

                class MySqlPeriodReturnRoutineDemo {
                    @StoredFunction
                    static Period defaultGrace() {
                        return Period.ofDays(2);
                    }
                }
                """);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                new TranspilationPipeline().transpile(
                        List.of(source),
                        List.of(),
                        List.of("mysql"),
                        List.of("app"),
                        true));

        assertTrue(error.getMessage().contains("cannot return a period-like value"));
    }

    @Test
    void emitsRecordModelArtifactsBeforeRoutineArtifacts() throws Exception {
        Path source = tempDir.resolve("RecordModelDemo.java");
        Files.writeString(source, """
                import titan.dsl.StoredProcedure;

                class RecordModelDemo {
                    record CustomerSnapshot(long id, String email) {}

                    @StoredProcedure
                    static void sync() {
                    }
                }
                """);

        List<TranspilationPipeline.GeneratedSql> generated = new TranspilationPipeline().transpile(
                List.of(source),
                List.of(),
                List.of("postgresql", "mysql"),
                List.of("app"),
                true);

        assertEquals(4, generated.size());

        TranspilationPipeline.GeneratedSql firstPostgres = generated.stream()
                .filter(sql -> sql.target().equals("postgresql"))
                .findFirst()
                .orElseThrow();
        assertEquals("CustomerSnapshot", firstPostgres.methodName());
        assertTrue(firstPostgres.sql().contains("CREATE TYPE \"app\".\"__record_record_model_demo_customer_snapshot\" AS"));

        TranspilationPipeline.GeneratedSql firstMySql = generated.stream()
                .filter(sql -> sql.target().equals("mysql"))
                .findFirst()
                .orElseThrow();
        assertEquals("CustomerSnapshot", firstMySql.methodName());
        assertTrue(firstMySql.sql().contains("CREATE FUNCTION `app`.`__record_record_model_demo_customer_snapshot__new`("));
    }

    @Test
    void transpilesClassicForLoopAndAssignmentsIntoProcedureSql() throws Exception {
        Path source = tempDir.resolve("LoopAssignmentPipelineDemo.java");
        Files.writeString(source, """
                import titan.dsl.StoredProcedure;

                class LoopAssignmentPipelineDemo {
                    @StoredProcedure
                    static void run(int start) {
                        int total = start;
                        total = total + 1;

                        for (int i = 0; i < 3; i++) {
                            total += i;
                        }
                    }
                }
                """);

        List<TranspilationPipeline.GeneratedSql> generated = new TranspilationPipeline().transpile(
                List.of(source),
                List.of(),
                List.of("postgresql", "mysql"),
                List.of("app"),
                true);

        String postgresSql = generated.stream()
                .filter(sql -> sql.target().equals("postgresql") && sql.methodName().equals("run"))
                .findFirst()
                .orElseThrow()
                .sql();
        String mysqlSql = generated.stream()
                .filter(sql -> sql.target().equals("mysql") && sql.methodName().equals("run"))
                .findFirst()
                .orElseThrow()
                .sql();

        // Phase 2.1: the classic counting loop is recognized and lowered to ForRangeStatement,
        // emitted as a native FOR on PostgreSQL and the increment-first labeled-LOOP desugar
        // on MySQL (which has no FOR).
        assertTrue(postgresSql.contains("CREATE OR REPLACE PROCEDURE \"app\".\"run\"(p_start INTEGER)"));
        assertTrue(postgresSql.contains("v_total INTEGER;"));
        assertTrue(postgresSql.contains("v_total := p_start;"));
        assertTrue(postgresSql.contains("v_total := (v_total + 1);"));
        assertTrue(postgresSql.contains("v_i INTEGER;"));
        assertTrue(postgresSql.contains("FOR v_i IN 0..2 LOOP"), postgresSql);
        assertTrue(postgresSql.contains("v_total := (v_total + v_i);"));
        assertFalse(postgresSql.contains("WHILE "), postgresSql);

        assertTrue(mysqlSql.contains("CREATE PROCEDURE `app`.`run`(IN p_start INT)"));
        assertTrue(mysqlSql.contains("DECLARE v_total INT;"));
        assertTrue(mysqlSql.contains("SET v_total = p_start;"));
        assertTrue(mysqlSql.contains("SET v_total = (v_total + 1);"));
        assertTrue(mysqlSql.contains("DECLARE v_i INT;"));
        assertTrue(mysqlSql.contains("SET v_i = 0 - 1;"), mysqlSql);
        assertTrue(mysqlSql.contains("titan_loop_1: LOOP"), mysqlSql);
        assertTrue(mysqlSql.contains("SET v_i = v_i + 1;"), mysqlSql);
        assertTrue(mysqlSql.contains("IF v_i > 2 THEN LEAVE titan_loop_1; END IF;"), mysqlSql);
        assertTrue(mysqlSql.contains("SET v_total = (v_total + v_i);"));
        assertTrue(mysqlSql.contains("END LOOP titan_loop_1;"), mysqlSql);
    }

    @Test
    void transpilesEnhancedForLoopIntoDialectSpecificProcedureSql() throws Exception {
        Path source = tempDir.resolve("EnhancedForPipelineDemo.java");
        Files.writeString(source, """
                import titan.dsl.StoredProcedure;

                class EnhancedForPipelineDemo {
                    @StoredProcedure
                    static void run(int[] values) {
                        int total = 0;
                        for (int value : values) {
                            total += value;
                        }
                    }
                }
                """);

        List<TranspilationPipeline.GeneratedSql> generated = new TranspilationPipeline().transpile(
                List.of(source),
                List.of(),
                List.of("postgresql", "mysql"),
                List.of("app"),
                true);

        String postgresSql = generated.stream()
                .filter(sql -> sql.target().equals("postgresql") && sql.methodName().equals("run"))
                .findFirst()
                .orElseThrow()
                .sql();
        String mysqlSql = generated.stream()
                .filter(sql -> sql.target().equals("mysql") && sql.methodName().equals("run"))
                .findFirst()
                .orElseThrow()
                .sql();

        assertTrue(postgresSql.contains("CREATE OR REPLACE PROCEDURE \"app\".\"run\"(p_values INTEGER[])"));
        assertTrue(postgresSql.contains("v_total INTEGER;"));
        assertTrue(postgresSql.contains("v_total := 0;"));
        assertTrue(postgresSql.contains("v_value INTEGER;"));
        assertTrue(postgresSql.contains("FOREACH v_value IN ARRAY p_values LOOP"));
        assertTrue(postgresSql.contains("v_total := (v_total + v_value);"));

        assertTrue(mysqlSql.contains("CREATE PROCEDURE `app`.`run`(IN p_values JSON)"));
        assertTrue(mysqlSql.contains("DECLARE v_total INT;"));
        assertTrue(mysqlSql.contains("SET v_total = 0;"));
        assertTrue(mysqlSql.contains("DECLARE v_value INT;"));
        assertTrue(mysqlSql.contains("CREATE TEMPORARY TABLE titan_iter_v_value (value INT);"));
        assertTrue(mysqlSql.contains("JSON_TABLE(p_values, '$[*]' COLUMNS (value INT PATH '$'))"));
        assertTrue(mysqlSql.contains("FETCH cur_v_value INTO v_value;"));
        assertTrue(mysqlSql.contains("SET v_total = (v_total + v_value);"));
    }

    @Test
    void transpilesArrayParameterTypeMatrixIntoProcedureSql() throws Exception {
        Path source = tempDir.resolve("ArrayParameterMatrixPipelineDemo.java");
        Files.writeString(source, """
                import titan.dsl.StoredProcedure;

                class ArrayParameterMatrixPipelineDemo {
                    @StoredProcedure
                    static void runLongs(long[] values) {
                        long total = 0L;
                        for (long value : values) {
                            total += value;
                        }
                    }

                    @StoredProcedure
                    static void runStrings(String[] values) {
                        String last = "";
                        for (String value : values) {
                            last = value;
                        }
                    }
                }
                """);

        List<TranspilationPipeline.GeneratedSql> generated = new TranspilationPipeline().transpile(
                List.of(source),
                List.of(),
                List.of("postgresql", "mysql"),
                List.of("app"),
                true);

        String postgresLongsSql = generated.stream()
                .filter(sql -> sql.target().equals("postgresql") && sql.methodName().equals("runLongs"))
                .findFirst()
                .orElseThrow()
                .sql();
        String mysqlLongsSql = generated.stream()
                .filter(sql -> sql.target().equals("mysql") && sql.methodName().equals("runLongs"))
                .findFirst()
                .orElseThrow()
                .sql();
        String postgresStringsSql = generated.stream()
                .filter(sql -> sql.target().equals("postgresql") && sql.methodName().equals("runStrings"))
                .findFirst()
                .orElseThrow()
                .sql();
        String mysqlStringsSql = generated.stream()
                .filter(sql -> sql.target().equals("mysql") && sql.methodName().equals("runStrings"))
                .findFirst()
                .orElseThrow()
                .sql();

        assertTrue(postgresLongsSql.contains("CREATE OR REPLACE PROCEDURE \"app\".\"run_longs\"(p_values BIGINT[])"));
        assertTrue(postgresLongsSql.contains("v_value BIGINT;"));
        assertTrue(postgresLongsSql.contains("FOREACH v_value IN ARRAY p_values LOOP"));
        assertTrue(postgresLongsSql.contains("v_total := (v_total + v_value);"));

        assertTrue(mysqlLongsSql.contains("CREATE PROCEDURE `app`.`run_longs`(IN p_values JSON)"));
        assertTrue(mysqlLongsSql.contains("DECLARE v_value BIGINT;"));
        assertTrue(mysqlLongsSql.contains("CREATE TEMPORARY TABLE titan_iter_v_value (value BIGINT);"));
        assertTrue(mysqlLongsSql.contains("JSON_TABLE(p_values, '$[*]' COLUMNS (value BIGINT PATH '$'))"));

        assertTrue(postgresStringsSql.contains("CREATE OR REPLACE PROCEDURE \"app\".\"run_strings\"(p_values TEXT[])"));
        assertTrue(postgresStringsSql.contains("v_value TEXT;"));
        assertTrue(postgresStringsSql.contains("FOREACH v_value IN ARRAY p_values LOOP"));
        assertTrue(postgresStringsSql.contains("v_last := v_value;"));

        assertTrue(mysqlStringsSql.contains("CREATE PROCEDURE `app`.`run_strings`(IN p_values JSON)"));
        assertTrue(mysqlStringsSql.contains("DECLARE v_value TEXT;"));
        assertTrue(mysqlStringsSql.contains("CREATE TEMPORARY TABLE titan_iter_v_value (value TEXT);"));
        assertTrue(mysqlStringsSql.contains("JSON_TABLE(p_values, '$[*]' COLUMNS (value TEXT PATH '$'))"));
        assertTrue(mysqlStringsSql.contains("SET v_last = v_value;"));
    }

    @Test
    void rejectsIterableEntryPointParameterInPipeline() throws Exception {
        Path source = tempDir.resolve("IterableParameterPipelineDemo.java");
        Files.writeString(source, """
                import titan.dsl.StoredProcedure;
                import java.util.List;

                class IterableParameterPipelineDemo {
                    @StoredProcedure
                    static void run(List<Integer> values) {
                        for (int value : values) {
                        }
                    }
                }
                """);

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> new TranspilationPipeline().transpile(
                        List.of(source),
                        List.of(),
                        List.of("postgresql", "mysql"),
                        List.of("app"),
                        true)
        );

        assertTrue(exception.getMessage().contains("TITAN-E001"));
        assertTrue(exception.getMessage().contains("entry-point Iterable parameter type 'java.util.List<java.lang.Integer>'"));
    }

    @Test
    void transpilesWhileLoopIntoProcedureSql() throws Exception {
        Path source = tempDir.resolve("WhilePipelineDemo.java");
        Files.writeString(source, """
                import titan.dsl.StoredProcedure;

                class WhilePipelineDemo {
                    @StoredProcedure
                    static void run(int start) {
                        int total = start;
                        while (total < 5) {
                            total = total + 2;
                        }
                    }
                }
                """);

        List<TranspilationPipeline.GeneratedSql> generated = new TranspilationPipeline().transpile(
                List.of(source),
                List.of(),
                List.of("postgresql", "mysql"),
                List.of("app"),
                true);

        String postgresSql = generated.stream()
                .filter(sql -> sql.target().equals("postgresql") && sql.methodName().equals("run"))
                .findFirst()
                .orElseThrow()
                .sql();
        String mysqlSql = generated.stream()
                .filter(sql -> sql.target().equals("mysql") && sql.methodName().equals("run"))
                .findFirst()
                .orElseThrow()
                .sql();

        assertTrue(postgresSql.contains("CREATE OR REPLACE PROCEDURE \"app\".\"run\"(p_start INTEGER)"));
        assertTrue(postgresSql.contains("v_total INTEGER;"));
        assertTrue(postgresSql.contains("v_total := p_start;"));
        assertTrue(postgresSql.contains("WHILE "));
        assertTrue(postgresSql.contains("v_total < 5"));
        assertTrue(postgresSql.contains("v_total := (v_total + 2);"));

        assertTrue(mysqlSql.contains("CREATE PROCEDURE `app`.`run`(IN p_start INT)"));
        assertTrue(mysqlSql.contains("DECLARE v_total INT;"));
        assertTrue(mysqlSql.contains("SET v_total = p_start;"));
        assertTrue(mysqlSql.contains("WHILE "));
        assertTrue(mysqlSql.contains("v_total < 5"));
        assertTrue(mysqlSql.contains("SET v_total = (v_total + 2);"));
    }

    @Test
    void transpilesDoWhileLoopIntoProcedureSql() throws Exception {
        Path source = tempDir.resolve("DoWhilePipelineDemo.java");
        Files.writeString(source, """
                import titan.dsl.StoredProcedure;

                class DoWhilePipelineDemo {
                    @StoredProcedure
                    static void run(int start) {
                        int total = start;
                        do {
                            total = total - 1;
                        } while (total > 0);
                    }
                }
                """);

        List<TranspilationPipeline.GeneratedSql> generated = new TranspilationPipeline().transpile(
                List.of(source),
                List.of(),
                List.of("postgresql", "mysql"),
                List.of("app"),
                true);

        String postgresSql = generated.stream()
                .filter(sql -> sql.target().equals("postgresql") && sql.methodName().equals("run"))
                .findFirst()
                .orElseThrow()
                .sql();
        String mysqlSql = generated.stream()
                .filter(sql -> sql.target().equals("mysql") && sql.methodName().equals("run"))
                .findFirst()
                .orElseThrow()
                .sql();

        // Phase 2.1 (audit D12): the do-while condition is bound to one generated temp per
        // iteration at the top of a plain LOOP (so `continue` re-tests it), and the temp is
        // tested twice (IS NULL / = FALSE) instead of duplicating the lowered condition.
        assertTrue(postgresSql.contains("CREATE OR REPLACE PROCEDURE \"app\".\"run\"(p_start INTEGER)"));
        assertTrue(postgresSql.contains("v_total INTEGER;"));
        assertTrue(postgresSql.contains("v_total := p_start;"));
        assertTrue(postgresSql.contains("__titan_dowhile_cond_"), postgresSql);
        assertTrue(postgresSql.contains("__titan_dowhile_first_"), postgresSql);
        assertTrue(postgresSql.contains("LOOP"));
        assertTrue(postgresSql.contains(" := (v_total > 0);"), postgresSql);
        assertTrue(postgresSql.contains("IS NULL OR"), postgresSql);
        assertTrue(postgresSql.contains("EXIT;"), postgresSql);
        assertTrue(postgresSql.contains("v_total := (v_total - 1);"));

        assertTrue(mysqlSql.contains("CREATE PROCEDURE `app`.`run`(IN p_start INT)"));
        assertTrue(mysqlSql.contains("DECLARE v_total INT;"));
        assertTrue(mysqlSql.contains("SET v_total = p_start;"));
        assertTrue(mysqlSql.contains("__titan_dowhile_cond_"), mysqlSql);
        assertTrue(mysqlSql.contains("__titan_dowhile_first_"), mysqlSql);
        assertTrue(mysqlSql.contains("titan_loop_1: LOOP"), mysqlSql);
        assertTrue(mysqlSql.contains(" = (v_total > 0);"), mysqlSql);
        assertTrue(mysqlSql.contains("IS NULL OR"), mysqlSql);
        assertTrue(mysqlSql.contains("LEAVE titan_loop_1;"), mysqlSql);
        assertTrue(mysqlSql.contains("END LOOP titan_loop_1;"), mysqlSql);
        assertTrue(mysqlSql.contains("SET v_total = (v_total - 1);"));
    }

    @Test
    void transpilesSwitchStatementIntoProcedureSql() throws Exception {
        Path source = tempDir.resolve("SwitchStatementPipelineDemo.java");
        Files.writeString(source, """
                import titan.dsl.StoredProcedure;

                class SwitchStatementPipelineDemo {
                    @StoredProcedure
                    static void run(int input) {
                        int score = 0;
                        switch (input) {
                            case 1, 2 -> score = 10;
                            case 3 -> score += 5;
                            default -> score = 99;
                        }
                    }
                }
                """);

        List<TranspilationPipeline.GeneratedSql> generated = new TranspilationPipeline().transpile(
                List.of(source),
                List.of(),
                List.of("postgresql", "mysql"),
                List.of("app"),
                true);

        String postgresSql = generated.stream()
                .filter(sql -> sql.target().equals("postgresql") && sql.methodName().equals("run"))
                .findFirst()
                .orElseThrow()
                .sql();
        String mysqlSql = generated.stream()
                .filter(sql -> sql.target().equals("mysql") && sql.methodName().equals("run"))
                .findFirst()
                .orElseThrow()
                .sql();

        assertTrue(postgresSql.contains("CREATE OR REPLACE PROCEDURE \"app\".\"run\"(p_input INTEGER)"));
        assertTrue(postgresSql.contains("v_score INTEGER;"));
        assertTrue(postgresSql.contains("v_score := 0;"));
        assertTrue(postgresSql.contains("__titan_switch_value_"));
        assertTrue(postgresSql.contains("IF "));
        assertTrue(postgresSql.contains("ELSIF "));
        assertTrue(postgresSql.contains("ELSE"));
        assertTrue(postgresSql.contains("v_score := 10;"));
        assertTrue(postgresSql.contains("v_score := (v_score + 5);"));
        assertTrue(postgresSql.contains("v_score := 99;"));

        assertTrue(mysqlSql.contains("CREATE PROCEDURE `app`.`run`(IN p_input INT)"));
        assertTrue(mysqlSql.contains("DECLARE v_score INT;"));
        assertTrue(mysqlSql.contains("SET v_score = 0;"));
        assertTrue(mysqlSql.contains("__titan_switch_value_"));
        assertTrue(mysqlSql.contains("IF "));
        assertTrue(mysqlSql.contains("ELSEIF "));
        assertTrue(mysqlSql.contains("ELSE"));
        assertTrue(mysqlSql.contains("SET v_score = 10;"));
        assertTrue(mysqlSql.contains("SET v_score = (v_score + 5);"));
        assertTrue(mysqlSql.contains("SET v_score = 99;"));
    }

    @Test
    void transpilesSwitchExpressionIntoFunctionSql() throws Exception {
        Path source = tempDir.resolve("SwitchExpressionPipelineDemo.java");
        Files.writeString(source, """
                import titan.dsl.StoredFunction;

                class SwitchExpressionPipelineDemo {
                    @StoredFunction
                    static int run(int input) {
                        return switch (input) {
                            case 1, 2 -> 10;
                            case 3 -> {
                                yield 20;
                            }
                            default -> 0;
                        };
                    }
                }
                """);

        List<TranspilationPipeline.GeneratedSql> generated = new TranspilationPipeline().transpile(
                List.of(source),
                List.of(),
                List.of("postgresql", "mysql"),
                List.of("app"),
                true);

        String postgresSql = generated.stream()
                .filter(sql -> sql.target().equals("postgresql") && sql.methodName().equals("run"))
                .findFirst()
                .orElseThrow()
                .sql();
        String mysqlSql = generated.stream()
                .filter(sql -> sql.target().equals("mysql") && sql.methodName().equals("run"))
                .findFirst()
                .orElseThrow()
                .sql();

        assertTrue(postgresSql.contains("CREATE OR REPLACE FUNCTION \"app\".\"run\"(p_input INTEGER)"));
        assertTrue(postgresSql.contains("RETURNS INTEGER"));
        assertTrue(postgresSql.contains("RETURN CASE WHEN"));
        assertTrue(postgresSql.contains("p_input = 1"));
        assertTrue(postgresSql.contains("p_input = 2"));
        assertTrue(postgresSql.contains("THEN 10"));
        assertTrue(postgresSql.contains("p_input = 3"));
        assertTrue(postgresSql.contains("THEN 20"));
        assertTrue(postgresSql.contains("ELSE 0 END"));

        assertTrue(mysqlSql.contains("CREATE FUNCTION `app`.`run`(p_input INT)"));
        assertTrue(mysqlSql.contains("RETURNS INT"));
        assertTrue(mysqlSql.contains("SET __titan_return_value = CASE WHEN"));
        assertTrue(mysqlSql.contains("p_input = 1"));
        assertTrue(mysqlSql.contains("p_input = 2"));
        assertTrue(mysqlSql.contains("THEN 10"));
        assertTrue(mysqlSql.contains("p_input = 3"));
        assertTrue(mysqlSql.contains("THEN 20"));
        assertTrue(mysqlSql.contains("ELSE 0 END"));
    }

    @Test
    void transpilesEarlyReturnInsideIfIntoFunctionSql() throws Exception {
        Path source = tempDir.resolve("IfReturnFunctionPipelineDemo.java");
        Files.writeString(source, """
                import titan.dsl.StoredFunction;

                class IfReturnFunctionPipelineDemo {
                    @StoredFunction
                    static int run(int input) {
                        if (input > 0) {
                            return 1;
                        }
                        return 2;
                    }
                }
                """);

        List<TranspilationPipeline.GeneratedSql> generated = new TranspilationPipeline().transpile(
                List.of(source),
                List.of(),
                List.of("postgresql", "mysql"),
                List.of("app"),
                true);

        String postgresSql = generated.stream()
                .filter(sql -> sql.target().equals("postgresql") && sql.methodName().equals("run"))
                .findFirst()
                .orElseThrow()
                .sql();
        String mysqlSql = generated.stream()
                .filter(sql -> sql.target().equals("mysql") && sql.methodName().equals("run"))
                .findFirst()
                .orElseThrow()
                .sql();

        assertTrue(postgresSql.contains("CREATE OR REPLACE FUNCTION \"app\".\"run\"(p_input INTEGER)"));
        assertTrue(postgresSql.contains("RETURNS INTEGER"));
        assertTrue(postgresSql.contains("IF "));
        assertTrue(postgresSql.contains("p_input > 0"));
        assertTrue(postgresSql.contains("RETURN 1;"));
        assertTrue(postgresSql.contains("RETURN 2;"));

        assertTrue(mysqlSql.contains("CREATE FUNCTION `app`.`run`(p_input INT)"));
        assertTrue(mysqlSql.contains("RETURNS INT"));
        assertTrue(mysqlSql.contains("IF "));
        assertTrue(mysqlSql.contains("p_input > 0"));
        assertTrue(mysqlSql.contains("SET __titan_return_value = 1;"));
        assertTrue(mysqlSql.contains("SET __titan_return_value = 2;"));
    }

    @Test
    void transpilesFunctionReturnAfterLoopStateChanges() throws Exception {
        Path source = tempDir.resolve("LoopReturnFunctionPipelineDemo.java");
        Files.writeString(source, """
                import titan.dsl.StoredFunction;

                class LoopReturnFunctionPipelineDemo {
                    @StoredFunction
                    static int run(int start) {
                        int total = start;
                        while (total < 5) {
                            total = total + 2;
                        }
                        return total;
                    }
                }
                """);

        List<TranspilationPipeline.GeneratedSql> generated = new TranspilationPipeline().transpile(
                List.of(source),
                List.of(),
                List.of("postgresql", "mysql"),
                List.of("app"),
                true);

        String postgresSql = generated.stream()
                .filter(sql -> sql.target().equals("postgresql") && sql.methodName().equals("run"))
                .findFirst()
                .orElseThrow()
                .sql();
        String mysqlSql = generated.stream()
                .filter(sql -> sql.target().equals("mysql") && sql.methodName().equals("run"))
                .findFirst()
                .orElseThrow()
                .sql();

        assertTrue(postgresSql.contains("CREATE OR REPLACE FUNCTION \"app\".\"run\"(p_start INTEGER)"));
        assertTrue(postgresSql.contains("RETURNS INTEGER"));
        assertTrue(postgresSql.contains("v_total INTEGER;"));
        assertTrue(postgresSql.contains("v_total := p_start;"));
        assertTrue(postgresSql.contains("WHILE "));
        assertTrue(postgresSql.contains("v_total < 5"));
        assertTrue(postgresSql.contains("v_total := (v_total + 2);"));
        assertTrue(postgresSql.contains("RETURN v_total;"));

        assertTrue(mysqlSql.contains("CREATE FUNCTION `app`.`run`(p_start INT)"));
        assertTrue(mysqlSql.contains("RETURNS INT"));
        assertTrue(mysqlSql.contains("DECLARE v_total INT;"));
        assertTrue(mysqlSql.contains("SET v_total = p_start;"));
        assertTrue(mysqlSql.contains("WHILE "));
        assertTrue(mysqlSql.contains("v_total < 5"));
        assertTrue(mysqlSql.contains("SET v_total = (v_total + 2);"));
        assertTrue(mysqlSql.contains("SET __titan_return_value = v_total;"));
    }

    @Test
    void transpilesTryFinallyIntoProcedureSql() throws Exception {
        Path source = tempDir.resolve("TryFinallyPipelineDemo.java");
        Files.writeString(source, """
                import titan.dsl.StoredProcedure;

                class TryFinallyPipelineDemo {
                    @StoredProcedure
                    static void run() {
                        int total = 0;
                        try {
                            throw new IllegalArgumentException("boom");
                        } finally {
                            total = total + 1;
                        }
                    }
                }
                """);

        List<TranspilationPipeline.GeneratedSql> generated = new TranspilationPipeline().transpile(
                List.of(source),
                List.of(),
                List.of("postgresql", "mysql"),
                List.of("app"),
                true);

        String postgresSql = generated.stream()
                .filter(sql -> sql.target().equals("postgresql") && sql.methodName().equals("run"))
                .findFirst()
                .orElseThrow()
                .sql();
        String mysqlSql = generated.stream()
                .filter(sql -> sql.target().equals("mysql") && sql.methodName().equals("run"))
                .findFirst()
                .orElseThrow()
                .sql();

        assertTrue(postgresSql.contains("BEGIN"));
        assertTrue(postgresSql.contains("EXCEPTION WHEN OTHERS THEN"));
        assertTrue(postgresSql.contains("__titan_saved_state"));
        assertTrue(postgresSql.contains("__titan_saved_message"));
        assertTrue(postgresSql.contains("v_total := (v_total + 1);"));
        assertTrue(postgresSql.contains("RAISE EXCEPTION USING ERRCODE = __titan_saved_state, MESSAGE = __titan_saved_message;"));

        assertTrue(mysqlSql.contains("DECLARE EXIT HANDLER FOR SQLEXCEPTION"));
        assertTrue(mysqlSql.contains("GET DIAGNOSTICS CONDITION 1 __titan_saved_state = RETURNED_SQLSTATE, __titan_saved_message = MESSAGE_TEXT;"));
        assertTrue(mysqlSql.contains("SET v_total = (v_total + 1);"));
        assertTrue(mysqlSql.contains("SET __titan_saved_message = IFNULL(__titan_saved_message, 'Unhandled exception in try block');"));
        assertTrue(mysqlSql.contains("SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = __titan_saved_message;"));
    }

    @Test
    void transpilesMultiCatchIntoProcedureSql() throws Exception {
        Path source = tempDir.resolve("TryMultiCatchPipelineDemo.java");
        Files.writeString(source, """
                import titan.dsl.StoredProcedure;

                class TryMultiCatchPipelineDemo {
                    @StoredProcedure
                    static void run() {
                        int total = 0;
                        try {
                            throw new ArithmeticException("boom");
                        } catch (ArithmeticException | NullPointerException ex) {
                            total = total + 1;
                        }
                    }
                }
                """);

        List<TranspilationPipeline.GeneratedSql> generated = new TranspilationPipeline().transpile(
                List.of(source),
                List.of(),
                List.of("postgresql", "mysql"),
                List.of("app"),
                true);

        String postgresSql = generated.stream()
                .filter(sql -> sql.target().equals("postgresql") && sql.methodName().equals("run"))
                .findFirst()
                .orElseThrow()
                .sql();
        String mysqlSql = generated.stream()
                .filter(sql -> sql.target().equals("mysql") && sql.methodName().equals("run"))
                .findFirst()
                .orElseThrow()
                .sql();

        assertTrue(postgresSql.contains("WHEN SQLSTATE '22012' OR SQLSTATE '45001' THEN"));
        assertTrue(postgresSql.contains("v_ex TEXT"));
        assertTrue(postgresSql.contains("v_ex := SQLERRM;"));
        assertTrue(postgresSql.contains("v_total := (v_total + 1);"));

        assertTrue(mysqlSql.contains("DECLARE v_ex TEXT"));
        assertTrue(mysqlSql.contains("DECLARE EXIT HANDLER FOR SQLSTATE '22012'"));
        assertTrue(mysqlSql.contains("DECLARE EXIT HANDLER FOR SQLSTATE '45001'"));
        assertTrue(mysqlSql.contains("SET v_ex = __titan_saved_message;"));
        assertTrue(mysqlSql.contains("SET v_total = (v_total + 1);"));
    }

    @Test
    void rejectsTryWithResourcesWhenResourceInitializerIsUnresolvedHelperCall() throws Exception {
        Path source = tempDir.resolve("TryWithResourcesPipelineDemo.java");
        Files.writeString(source, """
                import titan.dsl.StoredProcedure;

                class TryWithResourcesPipelineDemo {
                    @StoredProcedure
                    static void run() {
                        int total = 0;
                        try (CursorLike cur = open()) {
                            total = total + 1;
                        }
                    }

                    static CursorLike open() {
                        return new CursorLike();
                    }

                    static final class CursorLike implements AutoCloseable {
                        @Override
                        public void close() {
                        }
                    }
                }
                """);

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> new TranspilationPipeline().transpile(
                        List.of(source),
                        List.of(),
                        List.of("postgresql", "mysql"),
                        List.of("app"),
                        true)
        );

        assertTrue(exception.getMessage().contains("TITAN-E001"));
        assertTrue(exception.getMessage().contains("object or record construction in transpiled code"));
    }

    @Test
    void emitsRoutineParametersAndPhysicalDslTableNamesForTranspiledProcedures() throws Exception {
        Path source = tempDir.resolve("OwnerReadProcedures.java");
        Files.writeString(source, """
                import static titan.dsl.DSL.selectFrom;

                import titan.dsl.Column;
                import titan.dsl.Nullability;
                import titan.dsl.SQLType;
                import titan.dsl.StoredProcedure;
                import titan.dsl.Table;

                class OwnerReadProcedures {
                    static final OwnersTable OWNERS = new OwnersTable();

                    @StoredProcedure
                    static void ownersByLastNameLikePattern(String lastNamePattern) {
                        selectFrom(OWNERS)
                                .where(OWNERS.LAST_NAME.like(lastNamePattern))
                                .orderBy(OWNERS.LAST_NAME.asc())
                                .fetch();
                    }

                    static final class OwnersTable extends Table<Object> {
                        public final Column<Integer> ID = column("id", SQLType.INTEGER, Nullability.NOT_NULL);
                        public final Column<String> LAST_NAME = column("last_name", SQLType.VARCHAR, Nullability.NULLABLE);

                        OwnersTable() {
                            super("owners", "public");
                        }
                    }
                }
                """);

        String postgresSql = new TranspilationPipeline().transpile(
                        List.of(source),
                        List.of(),
                        List.of("postgresql"),
                        List.of("public"),
                        true)
                .getFirst()
                .sql();

        assertTrue(postgresSql.contains("CREATE OR REPLACE PROCEDURE \"public\".\"owners_by_last_name_like_pattern\"(p_last_name_pattern TEXT)"));
        assertTrue(postgresSql.contains("FROM \"owners\""));
        assertTrue(postgresSql.contains("\"owners\".\"last_name\" LIKE p_last_name_pattern"));
        assertTrue(postgresSql.contains("ORDER BY \"owners\".\"last_name\" ASC"));
        assertFalse(postgresSql.contains("\"OWNERS\".\"LAST_NAME\""));
        assertFalse(postgresSql.contains("lastNamePattern"));
    }

    @Test
    void emitsPhysicalNamesForCompiledGeneratedCatalogReferences() throws Exception {
        Path source = tempDir.resolve("GeneratedCatalogProcedures.java");
        Files.writeString(source, """
                import static titan.dsl.DSL.select;
                import static io.titan.transpiler.fixtures.generatedcatalog.GeneratedAccounts.ACCOUNTS;
                import static io.titan.transpiler.fixtures.generatedcatalog.GeneratedPlans.PLANS;

                import titan.dsl.StoredProcedure;

                class GeneratedCatalogProcedures {

                    @StoredProcedure
                    static void generatedCatalogJoin() {
                        select(ACCOUNTS.ID, ACCOUNTS.FIRST_NAME, PLANS.ID)
                                .from(ACCOUNTS)
                                .leftJoin(PLANS).on(ACCOUNTS.PLAN_ID.eqColumn(PLANS.ID))
                                .orderBy(ACCOUNTS.FIRST_NAME.asc(), PLANS.ID.asc())
                                .fetch();
                    }
                }
                """);

        String postgresSql = new TranspilationPipeline().transpile(
                        List.of(source),
                        currentJvmClasspathEntries(),
                        List.of("postgresql"),
                        List.of("public"),
                        true)
                .getFirst()
                .sql();

        // G1 (spike B1): the result is discarded (void @StoredProcedure statement), so PostgreSQL
        // lowers the query to PERFORM; the physical-name lowering this test pins is unaffected.
        assertTrue(postgresSql.contains("PERFORM \"accounts\".\"id\", \"accounts\".\"first_name\", \"plans\".\"id\" FROM \"accounts\" LEFT JOIN \"plans\" ON (\"accounts\".\"plan_id\" = \"plans\".\"id\") ORDER BY \"accounts\".\"first_name\" ASC, \"plans\".\"id\" ASC"));
        assertFalse(postgresSql.contains("\"ACCOUNTS\".\"ID\""));
        assertFalse(postgresSql.contains("FIRST_NAME"));
        assertFalse(postgresSql.contains("\"PLANS\".\"ID\""));
    }

    @Test
    void emitsPhysicalNamesForQualifiedGeneratedCatalogReferences() throws Exception {
        Path source = tempDir.resolve("QualifiedGeneratedCatalogProcedures.java");
        Files.writeString(source, """
                import static titan.dsl.DSL.select;

                import io.titan.transpiler.fixtures.generatedcatalog.GeneratedAccounts;
                import io.titan.transpiler.fixtures.generatedcatalog.GeneratedPlans;
                import titan.dsl.StoredProcedure;

                class QualifiedGeneratedCatalogProcedures {

                    @StoredProcedure
                    static void generatedCatalogJoin() {
                        select(GeneratedAccounts.ACCOUNTS.ID, GeneratedAccounts.ACCOUNTS.FIRST_NAME, GeneratedPlans.PLANS.ID)
                                .from(GeneratedAccounts.ACCOUNTS)
                                .leftJoin(GeneratedPlans.PLANS).on(GeneratedAccounts.ACCOUNTS.PLAN_ID.eqColumn(GeneratedPlans.PLANS.ID))
                                .orderBy(GeneratedAccounts.ACCOUNTS.FIRST_NAME.asc(), GeneratedPlans.PLANS.ID.asc())
                                .fetch();
                    }
                }
                """);

        String mysqlSql = new TranspilationPipeline().transpile(
                        List.of(source),
                        currentJvmClasspathEntries(),
                        List.of("mysql"),
                        List.of("public"),
                        true)
                .getFirst()
                .sql();

        assertTrue(mysqlSql.contains("SELECT `accounts`.`id`, `accounts`.`first_name`, `plans`.`id` FROM `accounts` LEFT JOIN `plans` ON (`accounts`.`plan_id` = `plans`.`id`) ORDER BY `accounts`.`first_name` ASC, `plans`.`id` ASC"));
        assertFalse(mysqlSql.contains("`GeneratedAccounts`.`ACCOUNTS`"));
        assertFalse(mysqlSql.contains("`GeneratedPlans`.`PLANS`"));
        assertFalse(mysqlSql.contains("`ACCOUNTS`.`ID`"));
    }

    private static List<Path> currentJvmClasspathEntries() {
        return Arrays.stream(System.getProperty("java.class.path", "").split(java.io.File.pathSeparator))
                .filter(entry -> !entry.isBlank())
                .map(Path::of)
                .toList();
    }

}
