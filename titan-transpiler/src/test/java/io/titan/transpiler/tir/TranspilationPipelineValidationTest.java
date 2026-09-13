package io.titan.transpiler.tir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.titan.transpiler.diagnostics.TitanDiagnostic;
import io.titan.transpiler.diagnostics.TitanErrorCode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TranspilationPipelineValidationTest {

    @TempDir
    Path tempDir;

    @Test
    void failsPipelineWhenJavaSourcesDoNotCompile() throws Exception {
        Path source = tempDir.resolve("BrokenFunction.java");
        Files.writeString(source, """
                import titan.dsl.StoredFunction;

                class BrokenFunction {
                    @StoredFunction
                    static int run(int value) {
                        Strng label = missingSymbol(value);
                        return value;
                    }
                }
                """);

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> new TranspilationPipeline().transpile(
                        List.of(source),
                        List.of(),
                        List.of("postgresql"),
                        List.of("public"),
                        true));

        assertTrue(exception.getMessage().startsWith("TITAN-E001: Java sources must compile before transpilation"),
                exception.getMessage());
        assertTrue(exception.getMessage().contains("BrokenFunction.java"), exception.getMessage());
        assertTrue(exception.getMessage().contains("Strng"), exception.getMessage());
    }

    @Test
    void aggregatesAllCompileErrorsWithFileAndLine() throws Exception {
        Path source = tempDir.resolve("MultiErrorFunction.java");
        Files.writeString(source, """
                import titan.dsl.StoredFunction;

                class MultiErrorFunction {
                    @StoredFunction
                    static int run(int value) {
                        Strng first = null;
                        Wibble second = null;
                        return value;
                    }
                }
                """);

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> new TranspilationPipeline().transpile(
                        List.of(source),
                        List.of(),
                        List.of("postgresql"),
                        List.of("public"),
                        true));

        assertTrue(exception.getMessage().contains("MultiErrorFunction.java:6"), exception.getMessage());
        assertTrue(exception.getMessage().contains("MultiErrorFunction.java:7"), exception.getMessage());
        assertTrue(exception.getMessage().contains("Strng"), exception.getMessage());
        assertTrue(exception.getMessage().contains("Wibble"), exception.getMessage());
    }

    @Test
    void cleanSourceStillTranspiles() throws Exception {
        Path source = tempDir.resolve("CleanFunction.java");
        Files.writeString(source, """
                import titan.dsl.StoredFunction;

                class CleanFunction {
                    @StoredFunction
                    static int run(int value) {
                        return value + 1;
                    }
                }
                """);

        List<TranspilationPipeline.GeneratedSql> generated = new TranspilationPipeline().transpile(
                List.of(source),
                List.of(),
                List.of("postgresql"),
                List.of("public"),
                true);

        assertFalse(generated.isEmpty());
        assertTrue(generated.getFirst().sql().contains("CREATE OR REPLACE FUNCTION"));
    }

    @Test
    void emptyStatementsTranspileToNoOpsOnBothDialects() throws Exception {
        // §10.1 audit: lone ';' statements (including as an if body, which lowers like an
        // empty '{}' block) must transpile cleanly on both dialects instead of failing the
        // statement default-deny.
        Path source = tempDir.resolve("EmptyStatementFunction.java");
        Files.writeString(source, """
                import titan.dsl.StoredFunction;

                class EmptyStatementFunction {
                    @StoredFunction
                    static int run(int value) {
                        ;
                        int doubled = value * 2;
                        if (doubled > 10) ;
                        ;
                        return doubled;
                    }
                }
                """);

        List<TranspilationPipeline.GeneratedSql> generated = new TranspilationPipeline().transpile(
                List.of(source),
                List.of(),
                List.of("postgresql", "mysql"),
                List.of("public"),
                true);

        assertEquals(2, generated.size());
        for (TranspilationPipeline.GeneratedSql sql : generated) {
            assertTrue(sql.sql().contains("CREATE"), sql.target() + ": " + sql.sql());
        }
    }

    @Test
    void dslDistinctStepLowersAndEmitsSelectDistinctOnBothDialects() throws Exception {
        // Phase 3.3 (audit D-7): .distinct() is now first-class DSL surface — it must lower to
        // SelectSql.distinct and emit SELECT DISTINCT on both dialects (this replaces the
        // pre-3.3 test that pinned the compile-gate rejection of the then-missing method).
        Path source = tempDir.resolve("DistinctChainFunction.java");
        Files.writeString(source, """
                import titan.dsl.*;
                import static titan.dsl.DSL.*;

                class DistinctChainFunction {
                    static final AccountsTable ACCOUNTS = new AccountsTable();

                    @StoredProcedure
                    public static void run() {
                        select(ACCOUNTS.ID)
                                .from(ACCOUNTS)
                                .distinct()
                                .fetch();
                    }

                    static final class AccountsTable extends Table<Object> {
                        public final Column<Integer> ID = column("id", SQLType.INTEGER, Nullability.NOT_NULL);

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
                List.of("public"),
                true);

        String postgresSql = generated.stream()
                .filter(sql -> sql.target().equals("postgresql"))
                .map(TranspilationPipeline.GeneratedSql::sql)
                .reduce("", String::concat);
        String mysqlSql = generated.stream()
                .filter(sql -> sql.target().equals("mysql"))
                .map(TranspilationPipeline.GeneratedSql::sql)
                .reduce("", String::concat);
        // G1 (spike B1): the select's result is discarded (a void @StoredProcedure statement), so
        // on PostgreSQL it lowers to PERFORM — a bare SELECT raises "no destination for result
        // data" at execute. PERFORM substitutes for the leading SELECT keyword and cannot carry
        // DISTINCT (irrelevant for a discarded result), so the distinct flag drops here. MySQL
        // accepts the bare SELECT statement and keeps SELECT DISTINCT (documented asymmetry).
        assertTrue(postgresSql.contains("PERFORM \"accounts\".\"id\" FROM \"accounts\""), postgresSql);
        assertFalse(postgresSql.contains("SELECT DISTINCT \"accounts\".\"id\""), postgresSql);
        assertTrue(mysqlSql.contains("SELECT DISTINCT `accounts`.`id` FROM `accounts`"), mysqlSql);
    }

    @Test
    void rejectsUnsupportedConstructEvenWithoutStrictMode() throws Exception {
        Path source = tempDir.resolve("BitwiseFunction.java");
        Files.writeString(source, """
                import titan.dsl.StoredFunction;

                class BitwiseFunction {
                    @StoredFunction
                    static int run(int value) {
                        return value & 1;
                    }
                }
                """);

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> new TranspilationPipeline().transpile(
                        List.of(source),
                        List.of(),
                        List.of("postgresql"),
                        List.of("public"),
                        false));

        assertTrue(exception.getMessage().contains("TITAN-E001"), exception.getMessage());
        assertTrue(exception.getMessage().contains("bitwise or shift operator"), exception.getMessage());
        assertTrue(exception.getMessage().contains("BitwiseFunction.java:6"), exception.getMessage());
    }

    @Test
    void rejectsUnsupportedConstructInStrictMode() throws Exception {
        Path source = tempDir.resolve("LambdaFunction.java");
        Files.writeString(source, """
                import titan.dsl.StoredFunction;
                import java.util.function.IntUnaryOperator;

                class LambdaFunction {
                    @StoredFunction
                    static int run(int value) {
                        IntUnaryOperator op = v -> v + 1;
                        return op.applyAsInt(value);
                    }
                }
                """);

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> new TranspilationPipeline().transpile(
                        List.of(source),
                        List.of(),
                        List.of("postgresql"),
                        List.of("public"),
                        true));

        assertTrue(exception.getMessage().contains("TITAN-E001"), exception.getMessage());
        assertTrue(exception.getMessage().contains("lambda expression"), exception.getMessage());
    }

    @Test
    void rejectsUnsupportedParameterTypeInsteadOfMappingToText() throws Exception {
        Path source = tempDir.resolve("UnsupportedParameterFunction.java");
        Files.writeString(source, """
                import java.util.Map;
                import titan.dsl.StoredFunction;

                class UnsupportedParameterFunction {
                    @StoredFunction
                    static int run(Map<String, Integer> lookup) {
                        return 1;
                    }
                }
                """);

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> new TranspilationPipeline().transpile(
                        List.of(source),
                        List.of(),
                        List.of("postgresql"),
                        List.of("public"),
                        true));

        assertTrue(exception.getMessage().contains("TITAN-E001"), exception.getMessage());
        assertTrue(exception.getMessage().contains("java.util.Map<java.lang.String,java.lang.Integer>"),
                exception.getMessage());
        assertTrue(exception.getMessage().contains("parameter 'lookup'"), exception.getMessage());
    }

    // ------------------------------------------------------------------
    // Plan 3.1: the pipeline wires its transpile targets into FeatureValidator,
    // so dialect-infeasible constructs fail at compile time with positions and
    // version-gated/lossy constructs surface as TITAN-W005 warnings.
    // ------------------------------------------------------------------

    @Test
    void mySqlTargetRejectsStringFormatAtCompileTimeWhilePostgresTargetAccepts() throws Exception {
        Path source = tempDir.resolve("FormatFunction.java");
        Files.writeString(source, """
                import titan.dsl.StoredFunction;

                class FormatFunction {
                    @StoredFunction
                    public static String label(int value) {
                        return String.format("v=%d", value);
                    }
                }
                """);

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> new TranspilationPipeline().transpile(
                        List.of(source),
                        List.of(),
                        List.of("postgresql", "mysql"),
                        List.of("public"),
                        true));

        assertTrue(exception.getMessage().contains("TITAN-E001"), exception.getMessage());
        assertTrue(exception.getMessage().contains("String.format is not supported by MySQL (any version)"),
                exception.getMessage());
        assertTrue(exception.getMessage().contains("FormatFunction.java:6"), exception.getMessage());

        // PostgreSQL-only targeting still transpiles String.format.
        List<TranspilationPipeline.GeneratedSql> generated = new TranspilationPipeline().transpile(
                List.of(source),
                List.of(),
                List.of("postgresql"),
                List.of("public"),
                true);
        assertFalse(generated.isEmpty());
    }

    @Test
    void mySqlTargetWarnsAboutIntersectVersionFloorThroughThePipelineSink() throws Exception {
        Path source = tempDir.resolve("IntersectChains.java");
        Files.writeString(source, """
                import titan.dsl.*;
                import static titan.dsl.DSL.*;

                class IntersectChains {
                    static final AccountsTable ACCOUNTS = new AccountsTable();
                    static final UsersTable USERS = new UsersTable();

                    @StoredProcedure
                    public static void run() {
                        select(ACCOUNTS.ID).from(ACCOUNTS)
                                .intersect(select(USERS.ACCOUNT_ID).from(USERS))
                                .fetch();
                    }

                    static final class AccountsTable extends Table<Object> {
                        final Column<Integer> ID = column("id", SQLType.INTEGER, Nullability.NOT_NULL);

                        AccountsTable() {
                            super("accounts", "public");
                        }
                    }

                    static final class UsersTable extends Table<Object> {
                        final Column<Integer> ACCOUNT_ID = column("account_id", SQLType.INTEGER, Nullability.NOT_NULL);

                        UsersTable() {
                            super("users", "public");
                        }
                    }
                }
                """);

        TranspilationPipeline pipeline = new TranspilationPipeline();
        List<TranspilationPipeline.GeneratedSql> generated = pipeline.transpile(
                List.of(source),
                List.of(),
                List.of("postgresql", "mysql"),
                List.of("public"),
                true);

        assertFalse(generated.isEmpty());
        TitanDiagnostic warning = pipeline.warnings().stream()
                .filter(diagnostic -> diagnostic.code() == TitanErrorCode.W005)
                .findFirst()
                .orElseThrow(() -> new AssertionError("expected a W005 warning, got: " + pipeline.warnings()));
        assertTrue(warning.message().contains("INTERSECT requires MySQL >= 8.0.31"), warning.message());
        assertTrue(warning.location().contains("IntersectChains.java:10"), warning.location());
    }

    @Test
    void postgresTargetWarnsAboutTheSecurityInvokerViewFloor() throws Exception {
        Path source = tempDir.resolve("SharedViewFloor.java");
        Files.writeString(source, """
                import titan.dsl.StoredProcedure;
                import titan.dsl.ViewDefinition;

                class SharedViewFloor {
                    @ViewDefinition(name = "active_accounts", shared = true)
                    static final String ACTIVE = "SELECT id, email FROM accounts WHERE active = true";

                    @StoredProcedure
                    static void refresh() {
                    }
                }
                """);

        // PostgreSQL views carry WITH (security_invoker = true), a PG-15+ view option: the
        // compatibility floor surfaces as a positioned TITAN-W005 naming the floor.
        TranspilationPipeline postgresPipeline = new TranspilationPipeline();
        postgresPipeline.transpile(
                List.of(source),
                List.of(),
                List.of("postgresql"),
                List.of("app"),
                true);
        TitanDiagnostic warning = postgresPipeline.warnings().stream()
                .filter(diagnostic -> diagnostic.code() == TitanErrorCode.W005)
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "expected a W005 security_invoker floor warning, got: " + postgresPipeline.warnings()));
        assertTrue(warning.message().contains("security_invoker views require PostgreSQL >= 15"),
                warning.message());
        assertTrue(warning.message().contains("active_accounts"), warning.message());
        assertTrue(warning.location() != null && warning.location().contains("SharedViewFloor.java"),
                String.valueOf(warning.location()));
        assertTrue(warning.suggestion().contains("PostgreSQL 15 or newer"), warning.suggestion());

        // MySQL emits SQL SECURITY INVOKER, supported on every targeted version: no floor warning.
        TranspilationPipeline mysqlPipeline = new TranspilationPipeline();
        mysqlPipeline.transpile(
                List.of(source),
                List.of(),
                List.of("mysql"),
                List.of("app"),
                true);
        assertTrue(mysqlPipeline.warnings().stream().noneMatch(d -> d.code() == TitanErrorCode.W005),
                mysqlPipeline.warnings().toString());
    }

    @Test
    void mySqlLossyCronApproximationIsAWarningThroughTheSinkAndACommentInTheArtifact() throws Exception {
        Path source = tempDir.resolve("WeekdayJob.java");
        Files.writeString(source, """
                import titan.dsl.ScheduledJob;

                class WeekdayJob {
                    @ScheduledJob(cron = "0 9 * * MON-FRI", name = "weekday_event")
                    public static void run() {
                        int beat = 1;
                    }
                }
                """);

        TranspilationPipeline pipeline = new TranspilationPipeline();
        List<TranspilationPipeline.GeneratedSql> generated = pipeline.transpile(
                List.of(source),
                List.of(),
                List.of("postgresql", "mysql"),
                List.of("public"),
                true);

        TitanDiagnostic warning = pipeline.warnings().stream()
                .filter(diagnostic -> diagnostic.code() == TitanErrorCode.W005)
                .findFirst()
                .orElseThrow(() -> new AssertionError("expected a W005 cron warning, got: " + pipeline.warnings()));
        // The warning names the original cron and the emitted approximation, positioned at the
        // entry point.
        assertTrue(warning.message().contains("'0 9 * * MON-FRI'"), warning.message());
        assertTrue(warning.message().contains("EVERY 1 DAY"), warning.message());
        assertTrue(warning.location().contains("WeekdayJob.java"), warning.location());

        // The SQL comment stays as defense-in-depth inside the generated artifact.
        String mysqlSql = generated.stream()
                .filter(sql -> sql.target().equals("mysql"))
                .map(TranspilationPipeline.GeneratedSql::sql)
                .findFirst()
                .orElseThrow();
        assertTrue(mysqlSql.contains("-- Cron expression '0 9 * * MON-FRI' cannot be represented natively"),
                mysqlSql);
        assertTrue(mysqlSql.contains("ON SCHEDULE EVERY 1 DAY STARTS CURRENT_TIMESTAMP"), mysqlSql);

        // PostgreSQL schedules the exact cron through pg_cron: no approximation warning.
        TranspilationPipeline postgresPipeline = new TranspilationPipeline();
        postgresPipeline.transpile(
                List.of(source),
                List.of(),
                List.of("postgresql"),
                List.of("public"),
                true);
        assertTrue(postgresPipeline.warnings().stream().noneMatch(d -> d.code() == TitanErrorCode.W005),
                postgresPipeline.warnings().toString());
    }

    @Test
    void mySqlTargetWarnsAboutTimestampTz2038RangeForSignaturesAndRecordFields() throws Exception {
        Path source = tempDir.resolve("InstantSignatures.java");
        Files.writeString(source, """
                import java.time.Instant;
                import titan.dsl.StoredFunction;

                class InstantSignatures {
                    record Snapshot(int id, Instant takenAt) {}

                    @StoredFunction
                    public static Instant echo(Instant moment) {
                        return moment;
                    }

                    @StoredFunction
                    public static int snapshotId(Snapshot snapshot) {
                        return snapshot.id();
                    }
                }
                """);

        TranspilationPipeline pipeline = new TranspilationPipeline();
        List<TranspilationPipeline.GeneratedSql> generated = pipeline.transpile(
                List.of(source),
                List.of(),
                List.of("postgresql", "mysql"),
                List.of("public"),
                true);

        List<TitanDiagnostic> rangeWarnings = pipeline.warnings().stream()
                .filter(diagnostic -> diagnostic.code() == TitanErrorCode.W005)
                .filter(diagnostic -> diagnostic.message().contains("2038-01-19"))
                .toList();
        assertTrue(rangeWarnings.stream().anyMatch(d -> d.message().contains("parameter 'moment'")
                        && d.message().contains("return value")),
                rangeWarnings.toString());
        assertTrue(rangeWarnings.stream().anyMatch(d -> d.message().contains("component 'takenAt'")
                        && d.message().contains("record InstantSignatures.Snapshot")),
                rangeWarnings.toString());
        for (TitanDiagnostic warning : rangeWarnings) {
            assertTrue(warning.location() != null && warning.location().contains("InstantSignatures.java"),
                    String.valueOf(warning.location()));
        }

        // E-13: the MySQL signature carries microsecond precision.
        String mysqlSql = generated.stream()
                .filter(sql -> sql.target().equals("mysql") && sql.sql().contains("echo"))
                .map(TranspilationPipeline.GeneratedSql::sql)
                .findFirst()
                .orElseThrow();
        assertTrue(mysqlSql.contains("TIMESTAMP(6)"), mysqlSql);

        // PostgreSQL TIMESTAMPTZ has no 2038 limit: no range warnings.
        TranspilationPipeline postgresPipeline = new TranspilationPipeline();
        postgresPipeline.transpile(
                List.of(source),
                List.of(),
                List.of("postgresql"),
                List.of("public"),
                true);
        assertTrue(postgresPipeline.warnings().stream().noneMatch(d -> d.message().contains("2038-01-19")),
                postgresPipeline.warnings().toString());
    }

    @Test
    void unknownStringMethodFailsTranspilationWithPositionedDiagnostic() throws Exception {
        // B-1 (TG-BLK-007): String.equalsIgnoreCase used to be emitted verbatim as an
        // `equalsignorecase(a, b)` SQL call — no validator diagnostic, no emit-time error,
        // failing only when the routine executed. The pipeline must reject it at compile time
        // with a positioned TITAN-E001 naming the method and the supported alternatives.
        Path source = tempDir.resolve("UnknownStringMethodFunction.java");
        Files.writeString(source, """
                import titan.dsl.StoredFunction;

                class UnknownStringMethodFunction {
                    @StoredFunction
                    public static boolean isAdmin(String role) {
                        return role.equalsIgnoreCase("admin");
                    }
                }
                """);

        io.titan.transpiler.diagnostics.TitanDiagnosticException exception = assertThrows(
                io.titan.transpiler.diagnostics.TitanDiagnosticException.class,
                () -> new TranspilationPipeline().transpile(
                        List.of(source),
                        List.of(),
                        List.of("postgresql", "mysql"),
                        List.of("public"),
                        true));

        assertTrue(exception.getMessage().contains("TITAN-E001"), exception.getMessage());
        assertTrue(exception.getMessage().contains("String method 'equalsIgnoreCase' has no SQL lowering"),
                exception.getMessage());
        assertTrue(exception.getMessage().contains("supported String methods"), exception.getMessage());
        assertTrue(exception.getMessage().contains("UnknownStringMethodFunction.java:6"), exception.getMessage());
        // The defect class: the broken call must never reach SQL emission.
        assertFalse(exception.getMessage().contains("equalsignorecase("), exception.getMessage());
    }
}
