package io.titan.transpiler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SqlAnnotationProcessorTest {

    @TempDir
    Path tempDir;

    @Test
    void extractsNamedParametersFromSqlAnnotations() throws Exception {
        Path sourceFile = tempDir.resolve("SqlAnnotatedEntryPoint.java");
        Files.writeString(sourceFile, """
                import titan.dsl.SQL;
                import titan.dsl.SqlDialect;
                import titan.dsl.StoredProcedure;

                class SqlAnnotatedEntryPoint {
                    @StoredProcedure
                    @SQL(dialect = SqlDialect.POSTGRESQL, value = "SELECT * FROM accounts WHERE id = :accountId AND status = :status")
                    public static void run(int accountId, String status) {}
                }
                """);

        ParsedSources parsed = new JavaSourceParser().parse(List.of(sourceFile), List.of(), "21", false);
        List<DiscoveredEntryPoint> entryPoints = new EntryPointDiscovery().discover(parsed);

        Map<String, List<SqlAnnotationProcessor.ProcessedSqlAnnotation>> processed =
                new SqlAnnotationProcessor().process(parsed, entryPoints);

        assertEquals(1, processed.size());
        String key = entryPoints.getFirst().methodSignatureKey();
        var sql = processed.get(key).getFirst();
        assertEquals("POSTGRESQL", sql.dialect());
        assertEquals(List.of("accountId", "status"), sql.parameterNames());
        assertEquals(true, sql.dslPromotionWarning());
        assertTrue(sql.warningMessage().contains("TITAN-W001: @SQL at "));
        assertTrue(sql.warningMessage().contains("contains no vendor-specific syntax and could be expressed through the Titan DSL."));
        assertTrue(sql.warningMessage().contains("Using the DSL provides type safety, schema validation, and dual-dialect support."));
        assertTrue(sql.sourceFile().endsWith("SqlAnnotatedEntryPoint.java"));
        assertTrue(sql.sourceLine() > 0);
    }

    @Test
    void localSqlAnnotationCanBindAnEarlierLocalVariable() throws Exception {
        Path sourceFile = tempDir.resolve("LocalSqlAnnotatedEntryPoint.java");
        Files.writeString(sourceFile, """
                import titan.dsl.SQL;
                import titan.dsl.SqlDialect;
                import titan.dsl.StoredProcedure;

                class LocalSqlAnnotatedEntryPoint {
                    @StoredProcedure
                    public static void run(String query) {
                        String response = query;
                        @SQL(dialect = SqlDialect.MYSQL, value = "SELECT :response AS response_json")
                        String emittedResponse = response;
                    }
                }
                """);

        ParsedSources parsed = new JavaSourceParser().parse(List.of(sourceFile), List.of(), "21", false);
        List<DiscoveredEntryPoint> entryPoints = new EntryPointDiscovery().discover(parsed);
        Map<String, List<SqlAnnotationProcessor.ProcessedSqlAnnotation>> processed =
                new SqlAnnotationProcessor().process(parsed, entryPoints);

        var sql = processed.get(entryPoints.getFirst().methodSignatureKey()).getFirst();
        assertEquals("MYSQL", sql.dialect());
        assertEquals(List.of("response"), sql.parameterNames());
    }

    @Test
    void skipsDslPromotionWarningForVendorSpecificSql() throws Exception {
        Path sourceFile = tempDir.resolve("VendorSqlAnnotatedEntryPoint.java");
        Files.writeString(sourceFile, """
                import titan.dsl.SQL;
                import titan.dsl.SqlDialect;
                import titan.dsl.StoredProcedure;

                class VendorSqlAnnotatedEntryPoint {
                    @StoredProcedure
                    @SQL(dialect = SqlDialect.POSTGRESQL, value = "SELECT ts_rank(search_vector, plainto_tsquery(:query)) FROM articles")
                    public static void run(String query) {}
                }
                """);

        ParsedSources parsed = new JavaSourceParser().parse(List.of(sourceFile), List.of(), "21", false);
        List<DiscoveredEntryPoint> entryPoints = new EntryPointDiscovery().discover(parsed);

        Map<String, List<SqlAnnotationProcessor.ProcessedSqlAnnotation>> processed =
                new SqlAnnotationProcessor().process(parsed, entryPoints);

        String key = entryPoints.getFirst().methodSignatureKey();
        var sql = processed.get(key).getFirst();
        assertEquals(false, sql.dslPromotionWarning());
        assertEquals(null, sql.warningMessage());
    }

    @Test
    void skipsDslPromotionWarningForPostgresTsvectorSyntax() throws Exception {
        Path sourceFile = tempDir.resolve("VendorSqlTsvectorEntryPoint.java");
        Files.writeString(sourceFile, """
                import titan.dsl.SQL;
                import titan.dsl.SqlDialect;
                import titan.dsl.StoredProcedure;

                class VendorSqlTsvectorEntryPoint {
                    @StoredProcedure
                    @SQL(dialect = SqlDialect.POSTGRESQL, value = "SELECT to_tsvector('english', body) @@ websearch_to_tsquery(:query) FROM articles")
                    public static void run(String query) {}
                }
                """);

        ParsedSources parsed = new JavaSourceParser().parse(List.of(sourceFile), List.of(), "21", false);
        List<DiscoveredEntryPoint> entryPoints = new EntryPointDiscovery().discover(parsed);

        Map<String, List<SqlAnnotationProcessor.ProcessedSqlAnnotation>> processed =
                new SqlAnnotationProcessor().process(parsed, entryPoints);

        String key = entryPoints.getFirst().methodSignatureKey();
        var sql = processed.get(key).getFirst();
        assertEquals(false, sql.dslPromotionWarning());
        assertEquals(null, sql.warningMessage());
    }

    @Test
    void emitsDslPromotionWarningForInsertSelectSql() throws Exception {
        Path sourceFile = tempDir.resolve("InsertSelectEntryPoint.java");
        Files.writeString(sourceFile, """
                import titan.dsl.SQL;
                import titan.dsl.SqlDialect;
                import titan.dsl.StoredProcedure;

                class InsertSelectEntryPoint {
                    @StoredProcedure
                    @SQL(dialect = SqlDialect.POSTGRESQL, value = "INSERT INTO archived_accounts (id, email) SELECT id, email FROM accounts WHERE active = :active")
                    public static void run(boolean active) {}
                }
                """);

        ParsedSources parsed = new JavaSourceParser().parse(List.of(sourceFile), List.of(), "21", false);
        List<DiscoveredEntryPoint> entryPoints = new EntryPointDiscovery().discover(parsed);

        Map<String, List<SqlAnnotationProcessor.ProcessedSqlAnnotation>> processed =
                new SqlAnnotationProcessor().process(parsed, entryPoints);

        String key = entryPoints.getFirst().methodSignatureKey();
        var sql = processed.get(key).getFirst();
        assertEquals(true, sql.dslPromotionWarning());
        assertTrue(sql.warningMessage().contains("TITAN-W001"));
    }

    @Test
    void skipsDslPromotionWarningForGenerateSeriesAndArrayAggOrderBy() throws Exception {
        Path sourceFile = tempDir.resolve("VendorSqlGenerateSeriesEntryPoint.java");
        Files.writeString(sourceFile, """
                import titan.dsl.SQL;
                import titan.dsl.SqlDialect;
                import titan.dsl.StoredProcedure;

                class VendorSqlGenerateSeriesEntryPoint {
                    @StoredProcedure
                    @SQL(dialect = SqlDialect.POSTGRESQL, value = "SELECT array_agg(id ORDER BY id) FROM generate_series(1, :limit) AS id")
                    public static void run(int limit) {}
                }
                """);

        ParsedSources parsed = new JavaSourceParser().parse(List.of(sourceFile), List.of(), "21", false);
        List<DiscoveredEntryPoint> entryPoints = new EntryPointDiscovery().discover(parsed);

        Map<String, List<SqlAnnotationProcessor.ProcessedSqlAnnotation>> processed =
                new SqlAnnotationProcessor().process(parsed, entryPoints);

        String key = entryPoints.getFirst().methodSignatureKey();
        var sql = processed.get(key).getFirst();
        assertEquals(false, sql.dslPromotionWarning());
        assertEquals(null, sql.warningMessage());
    }

    @Test
    void skipsDslPromotionWarningForPostgresTableSample() throws Exception {
        Path sourceFile = tempDir.resolve("VendorSqlTableSampleEntryPoint.java");
        Files.writeString(sourceFile, """
                import titan.dsl.SQL;
                import titan.dsl.SqlDialect;
                import titan.dsl.StoredProcedure;

                class VendorSqlTableSampleEntryPoint {
                    @StoredProcedure
                    @SQL(dialect = SqlDialect.POSTGRESQL, value = "SELECT id FROM accounts TABLESAMPLE SYSTEM (1) WHERE active = :active")
                    public static void run(boolean active) {}
                }
                """);

        ParsedSources parsed = new JavaSourceParser().parse(List.of(sourceFile), List.of(), "21", false);
        List<DiscoveredEntryPoint> entryPoints = new EntryPointDiscovery().discover(parsed);

        Map<String, List<SqlAnnotationProcessor.ProcessedSqlAnnotation>> processed =
                new SqlAnnotationProcessor().process(parsed, entryPoints);

        String key = entryPoints.getFirst().methodSignatureKey();
        var sql = processed.get(key).getFirst();
        assertEquals(false, sql.dslPromotionWarning());
        assertEquals(null, sql.warningMessage());
    }

    @Test
    void skipsDslPromotionWarningForPostgresListenNotify() throws Exception {
        Path sourceFile = tempDir.resolve("VendorSqlListenNotifyEntryPoint.java");
        Files.writeString(sourceFile, """
                import titan.dsl.SQL;
                import titan.dsl.SqlDialect;
                import titan.dsl.StoredProcedure;

                class VendorSqlListenNotifyEntryPoint {
                    @StoredProcedure
                    @SQL(dialect = SqlDialect.POSTGRESQL, value = "NOTIFY account_updates, 'changed'")
                    public static void run() {}
                }
                """);

        ParsedSources parsed = new JavaSourceParser().parse(List.of(sourceFile), List.of(), "21", false);
        List<DiscoveredEntryPoint> entryPoints = new EntryPointDiscovery().discover(parsed);

        Map<String, List<SqlAnnotationProcessor.ProcessedSqlAnnotation>> processed =
                new SqlAnnotationProcessor().process(parsed, entryPoints);

        String key = entryPoints.getFirst().methodSignatureKey();
        var sql = processed.get(key).getFirst();
        assertEquals(false, sql.dslPromotionWarning());
        assertEquals(null, sql.warningMessage());
    }

    @Test
    void acceptsShorthandValueAttributeInSqlAnnotation() throws Exception {
        Path sourceFile = tempDir.resolve("SqlShorthandEntryPoint.java");
        Files.writeString(sourceFile, """
                import titan.dsl.SQL;
                import titan.dsl.SqlDialect;
                import titan.dsl.StoredProcedure;

                class SqlShorthandEntryPoint {
                    @StoredProcedure
                    @SQL(dialect = SqlDialect.POSTGRESQL, "SELECT * FROM accounts WHERE id = :accountId")
                    public static void run(int accountId) {}
                }
                """);

        ParsedSources parsed = new JavaSourceParser().parse(List.of(sourceFile), List.of(), "21", false);
        List<DiscoveredEntryPoint> entryPoints = new EntryPointDiscovery().discover(parsed);

        Map<String, List<SqlAnnotationProcessor.ProcessedSqlAnnotation>> processed =
                new SqlAnnotationProcessor().process(parsed, entryPoints);

        String key = entryPoints.getFirst().methodSignatureKey();
        var sql = processed.get(key).getFirst();
        assertEquals("SELECT * FROM accounts WHERE id = :accountId", sql.sql());
        assertEquals(List.of("accountId"), sql.parameterNames());
    }

    @Test
    void doesNotTreatPostgresTypeCastsAsNamedParameters() throws Exception {
        Path sourceFile = tempDir.resolve("SqlTypeCastEntryPoint.java");
        Files.writeString(sourceFile, """
                import titan.dsl.SQL;
                import titan.dsl.SqlDialect;
                import titan.dsl.StoredProcedure;

                class SqlTypeCastEntryPoint {
                    @StoredProcedure
                    @SQL(dialect = SqlDialect.POSTGRESQL, value = "SELECT now()::date AS d")
                    public static void run() {}
                }
                """);

        ParsedSources parsed = new JavaSourceParser().parse(List.of(sourceFile), List.of(), "21", false);
        List<DiscoveredEntryPoint> entryPoints = new EntryPointDiscovery().discover(parsed);

        Map<String, List<SqlAnnotationProcessor.ProcessedSqlAnnotation>> processed =
                new SqlAnnotationProcessor().process(parsed, entryPoints);

        String key = entryPoints.getFirst().methodSignatureKey();
        var sql = processed.get(key).getFirst();
        assertEquals(List.of(), sql.parameterNames());
    }

    @Test
    void acceptsCompileTimeConstantSqlStringReference() throws Exception {
        Path sourceFile = tempDir.resolve("SqlConstantEntryPoint.java");
        Files.writeString(sourceFile, """
                import titan.dsl.SQL;
                import titan.dsl.SqlDialect;
                import titan.dsl.StoredProcedure;

                class SqlConstantEntryPoint {
                    private static final String FIND_ACCOUNT_SQL = "SELECT * FROM accounts WHERE id = :accountId";

                    @StoredProcedure
                    @SQL(dialect = SqlDialect.POSTGRESQL, value = FIND_ACCOUNT_SQL)
                    public static void run(int accountId) {}
                }
                """);

        ParsedSources parsed = new JavaSourceParser().parse(List.of(sourceFile), List.of(), "21", false);
        List<DiscoveredEntryPoint> entryPoints = new EntryPointDiscovery().discover(parsed);

        Map<String, List<SqlAnnotationProcessor.ProcessedSqlAnnotation>> processed =
                new SqlAnnotationProcessor().process(parsed, entryPoints);

        String key = entryPoints.getFirst().methodSignatureKey();
        var sql = processed.get(key).getFirst();
        assertEquals("SELECT * FROM accounts WHERE id = :accountId", sql.sql());
        assertEquals(List.of("accountId"), sql.parameterNames());
    }

    @Test
    void rejectsSqlStringConcatenationForSecurity() throws Exception {
        Path sourceFile = tempDir.resolve("SqlConcatEntryPoint.java");
        Files.writeString(sourceFile, """
                import titan.dsl.SQL;
                import titan.dsl.SqlDialect;
                import titan.dsl.StoredProcedure;

                class SqlConcatEntryPoint {
                    private static final String TABLE = "accounts";

                    @StoredProcedure
                    @SQL(dialect = SqlDialect.POSTGRESQL, value = "SELECT * FROM " + TABLE)
                    public static void run() {}
                }
                """);

        ParsedSources parsed = new JavaSourceParser().parse(List.of(sourceFile), List.of(), "21", false);
        List<DiscoveredEntryPoint> entryPoints = new EntryPointDiscovery().discover(parsed);

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> new SqlAnnotationProcessor().process(parsed, entryPoints)
        );

        assertTrue(exception.getMessage().contains("TITAN-E004"));
        assertTrue(exception.getMessage().contains("SqlConcatEntryPoint.java:9"));
    }

    @Test
    void rejectsReferencedCompileTimeConstantBuiltByConcatenation() throws Exception {
        Path sourceFile = tempDir.resolve("SqlConcatConstantEntryPoint.java");
        Files.writeString(sourceFile, """
                import titan.dsl.SQL;
                import titan.dsl.SqlDialect;
                import titan.dsl.StoredProcedure;

                class SqlConcatConstantEntryPoint {
                    private static final String FIND_SQL = "SELECT * FROM " + "accounts";

                    @StoredProcedure
                    @SQL(dialect = SqlDialect.POSTGRESQL, value = FIND_SQL)
                    public static void run() {}
                }
                """);

        ParsedSources parsed = new JavaSourceParser().parse(List.of(sourceFile), List.of(), "21", false);
        List<DiscoveredEntryPoint> entryPoints = new EntryPointDiscovery().discover(parsed);

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> new SqlAnnotationProcessor().process(parsed, entryPoints)
        );

        assertTrue(exception.getMessage().contains("TITAN-E004"));
        assertTrue(exception.getMessage().contains("SqlConcatConstantEntryPoint.java:9"));
    }

    @Test
    void rejectsReferencedCompileTimeConstantBuiltByConcatenationAcrossClassBoundary() throws Exception {
        Path constantsFile = tempDir.resolve("SqlConstants.java");
        Files.writeString(constantsFile, """
                class SqlConstants {
                    static final String FIND_SQL = "SELECT * FROM " + "accounts";
                }
                """);

        Path sourceFile = tempDir.resolve("SqlConcatConstantReferenceEntryPoint.java");
        Files.writeString(sourceFile, """
                import titan.dsl.SQL;
                import titan.dsl.SqlDialect;
                import titan.dsl.StoredProcedure;

                class SqlConcatConstantReferenceEntryPoint {
                    @StoredProcedure
                    @SQL(dialect = SqlDialect.POSTGRESQL, value = SqlConstants.FIND_SQL)
                    public static void run() {}
                }
                """);

        ParsedSources parsed = new JavaSourceParser().parse(List.of(constantsFile, sourceFile), List.of(), "21", false);
        List<DiscoveredEntryPoint> entryPoints = new EntryPointDiscovery().discover(parsed);

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> new SqlAnnotationProcessor().process(parsed, entryPoints)
        );

        assertTrue(exception.getMessage().contains("TITAN-E004"));
        assertTrue(exception.getMessage().contains("SqlConcatConstantReferenceEntryPoint.java:7"));
    }

    @Test
    void rejectsParenthesizedSqlStringConcatenationWithTitanE004() throws Exception {
        Path sourceFile = tempDir.resolve("SqlParenthesizedConcatEntryPoint.java");
        Files.writeString(sourceFile, """
                import titan.dsl.SQL;
                import titan.dsl.SqlDialect;
                import titan.dsl.StoredProcedure;

                class SqlParenthesizedConcatEntryPoint {
                    private static final String TABLE = "accounts";

                    @StoredProcedure
                    @SQL(dialect = SqlDialect.POSTGRESQL, value = ("SELECT * FROM " + TABLE))
                    public static void run() {}
                }
                """);

        ParsedSources parsed = new JavaSourceParser().parse(List.of(sourceFile), List.of(), "21", false);
        List<DiscoveredEntryPoint> entryPoints = new EntryPointDiscovery().discover(parsed);

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> new SqlAnnotationProcessor().process(parsed, entryPoints)
        );

        assertTrue(exception.getMessage().contains("TITAN-E004"));
        assertTrue(exception.getMessage().contains("SqlParenthesizedConcatEntryPoint.java:9"));
    }

    @Test
    void rejectsUnsupportedSqlDialectValue() throws Exception {
        Path sourceFile = tempDir.resolve("SqlUnsupportedDialectEntryPoint.java");
        Files.writeString(sourceFile, """
                import titan.dsl.SQL;
                import titan.dsl.StoredProcedure;

                class SqlUnsupportedDialectEntryPoint {
                    enum FakeDialect { LEGACY }

                    @StoredProcedure
                    @SQL(dialect = FakeDialect.LEGACY, value = "SELECT 1")
                    public static void run() {}
                }
                """);

        ParsedSources parsed = new JavaSourceParser().parse(List.of(sourceFile), List.of(), "21", false);
        List<DiscoveredEntryPoint> entryPoints = new EntryPointDiscovery().discover(parsed);

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> new SqlAnnotationProcessor().process(parsed, entryPoints)
        );

        assertTrue(exception.getMessage().contains("dialect must be one of"));
    }

    @Test
    void ignoresColonTokensInsideQuotedSqlLiterals() throws Exception {
        Path sourceFile = tempDir.resolve("SqlQuotedLiteralEntryPoint.java");
        Files.writeString(sourceFile, """
                import titan.dsl.SQL;
                import titan.dsl.SqlDialect;
                import titan.dsl.StoredProcedure;

                class SqlQuotedLiteralEntryPoint {
                    @StoredProcedure
                    @SQL(dialect = SqlDialect.POSTGRESQL, value = "SELECT ':ignored' AS marker, id FROM accounts WHERE id = :accountId")
                    public static void run(int accountId) {}
                }
                """);

        ParsedSources parsed = new JavaSourceParser().parse(List.of(sourceFile), List.of(), "21", false);
        List<DiscoveredEntryPoint> entryPoints = new EntryPointDiscovery().discover(parsed);

        Map<String, List<SqlAnnotationProcessor.ProcessedSqlAnnotation>> processed =
                new SqlAnnotationProcessor().process(parsed, entryPoints);

        String key = entryPoints.getFirst().methodSignatureKey();
        var sql = processed.get(key).getFirst();
        assertEquals(List.of("accountId"), sql.parameterNames());
    }

    @Test
    void ignoresColonTokensInsideSqlComments() throws Exception {
        Path sourceFile = tempDir.resolve("SqlCommentEntryPoint.java");
        Files.writeString(sourceFile, """
                import titan.dsl.SQL;
                import titan.dsl.SqlDialect;
                import titan.dsl.StoredProcedure;

                class SqlCommentEntryPoint {
                    @StoredProcedure
                    @SQL(dialect = SqlDialect.POSTGRESQL, value = "SELECT * FROM accounts /* :ignored */ WHERE id = :accountId")
                    public static void run(int accountId) {}
                }
                """);

        ParsedSources parsed = new JavaSourceParser().parse(List.of(sourceFile), List.of(), "21", false);
        List<DiscoveredEntryPoint> entryPoints = new EntryPointDiscovery().discover(parsed);

        Map<String, List<SqlAnnotationProcessor.ProcessedSqlAnnotation>> processed =
                new SqlAnnotationProcessor().process(parsed, entryPoints);

        String key = entryPoints.getFirst().methodSignatureKey();
        var sql = processed.get(key).getFirst();
        assertEquals(List.of("accountId"), sql.parameterNames());
    }

    @Test
    void ignoresColonTokensInsidePostgresDollarQuotedStrings() throws Exception {
        Path sourceFile = tempDir.resolve("SqlDollarQuotedEntryPoint.java");
        Files.writeString(sourceFile, """
                import titan.dsl.SQL;
                import titan.dsl.SqlDialect;
                import titan.dsl.StoredProcedure;

                class SqlDollarQuotedEntryPoint {
                    @StoredProcedure
                    @SQL(dialect = SqlDialect.POSTGRESQL, value = "SELECT $$:ignored$$ AS marker, id FROM accounts WHERE id = :accountId")
                    public static void run(int accountId) {}
                }
                """);

        ParsedSources parsed = new JavaSourceParser().parse(List.of(sourceFile), List.of(), "21", false);
        List<DiscoveredEntryPoint> entryPoints = new EntryPointDiscovery().discover(parsed);

        Map<String, List<SqlAnnotationProcessor.ProcessedSqlAnnotation>> processed =
                new SqlAnnotationProcessor().process(parsed, entryPoints);

        String key = entryPoints.getFirst().methodSignatureKey();
        var sql = processed.get(key).getFirst();
        assertEquals(List.of("accountId"), sql.parameterNames());
    }

    @Test
    void ignoresColonTokensInsideBacktickQuotedIdentifiers() throws Exception {
        Path sourceFile = tempDir.resolve("SqlBacktickIdentifierEntryPoint.java");
        Files.writeString(sourceFile, """
                import titan.dsl.SQL;
                import titan.dsl.SqlDialect;
                import titan.dsl.StoredProcedure;

                class SqlBacktickIdentifierEntryPoint {
                    @StoredProcedure
                    @SQL(dialect = SqlDialect.MYSQL, value = "SELECT `:ignored` AS marker FROM accounts WHERE id = :accountId")
                    public static void run(int accountId) {}
                }
                """);

        ParsedSources parsed = new JavaSourceParser().parse(List.of(sourceFile), List.of(), "21", false);
        List<DiscoveredEntryPoint> entryPoints = new EntryPointDiscovery().discover(parsed);

        Map<String, List<SqlAnnotationProcessor.ProcessedSqlAnnotation>> processed =
                new SqlAnnotationProcessor().process(parsed, entryPoints);

        String key = entryPoints.getFirst().methodSignatureKey();
        var sql = processed.get(key).getFirst();
        assertEquals(List.of("accountId"), sql.parameterNames());
    }

    @Test
    void rejectsSqlParameterThatIsNotInScope() throws Exception {
        Path sourceFile = tempDir.resolve("SqlAnnotatedEntryPoint.java");
        Files.writeString(sourceFile, """
                import titan.dsl.SQL;
                import titan.dsl.SqlDialect;
                import titan.dsl.StoredProcedure;

                class SqlAnnotatedEntryPoint {
                    @StoredProcedure
                    @SQL(dialect = SqlDialect.POSTGRESQL, value = "SELECT * FROM accounts WHERE id = :missing")
                    public static void run(int accountId) {}
                }
                """);

        ParsedSources parsed = new JavaSourceParser().parse(List.of(sourceFile), List.of(), "21", false);
        List<DiscoveredEntryPoint> entryPoints = new EntryPointDiscovery().discover(parsed);

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> new SqlAnnotationProcessor().process(parsed, entryPoints)
        );

        assertEquals("@SQL parameter :missing is not bound to a variable in scope", exception.getMessage());
    }

    @Test
    void rejectsSqlParameterDeclaredAfterAnnotatedVariable() throws Exception {
        Path sourceFile = tempDir.resolve("SqlAnnotatedEntryPoint.java");
        Files.writeString(sourceFile, """
                import titan.dsl.SQL;
                import titan.dsl.SqlDialect;
                import titan.dsl.StoredProcedure;

                class SqlAnnotatedEntryPoint {
                    @StoredProcedure
                    public static void run() {
                        @SQL(dialect = SqlDialect.POSTGRESQL, value = "SELECT * FROM accounts WHERE id = :accountId")
                        String ignored = "x";
                        int accountId = 42;
                    }
                }
                """);

        ParsedSources parsed = new JavaSourceParser().parse(List.of(sourceFile), List.of(), "21", false);
        List<DiscoveredEntryPoint> entryPoints = new EntryPointDiscovery().discover(parsed);

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> new SqlAnnotationProcessor().process(parsed, entryPoints)
        );

        assertEquals("@SQL parameter :accountId is not bound to a variable in scope", exception.getMessage());
    }

    @Test
    void rejectsSqlParameterFromOutOfScopeInnerBlockVariable() throws Exception {
        Path sourceFile = tempDir.resolve("SqlScopedVariableEntryPoint.java");
        Files.writeString(sourceFile, """
                import titan.dsl.SQL;
                import titan.dsl.SqlDialect;
                import titan.dsl.StoredProcedure;

                class SqlScopedVariableEntryPoint {
                    @StoredProcedure
                    public static void run(boolean include) {
                        if (include) {
                            int accountId = 42;
                        }

                        @SQL(dialect = SqlDialect.POSTGRESQL, value = "SELECT * FROM accounts WHERE id = :accountId")
                        String ignored = "x";
                    }
                }
                """);

        ParsedSources parsed = new JavaSourceParser().parse(List.of(sourceFile), List.of(), "21", false);
        List<DiscoveredEntryPoint> entryPoints = new EntryPointDiscovery().discover(parsed);

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> new SqlAnnotationProcessor().process(parsed, entryPoints)
        );

        assertEquals("@SQL parameter :accountId is not bound to a variable in scope", exception.getMessage());
    }

    @Test
    void skipsDslPromotionWarningForMalformedSqlWithUnbalancedParens() throws Exception {
        Path sourceFile = tempDir.resolve("MalformedSqlEntryPoint.java");
        Files.writeString(sourceFile, """
                import titan.dsl.SQL;
                import titan.dsl.SqlDialect;
                import titan.dsl.StoredProcedure;

                class MalformedSqlEntryPoint {
                    @StoredProcedure
                    @SQL(dialect = SqlDialect.POSTGRESQL, value = "SELECT * FROM accounts WHERE (id = :accountId")
                    public static void run(int accountId) {}
                }
                """);

        ParsedSources parsed = new JavaSourceParser().parse(List.of(sourceFile), List.of(), "21", false);
        List<DiscoveredEntryPoint> entryPoints = new EntryPointDiscovery().discover(parsed);

        Map<String, List<SqlAnnotationProcessor.ProcessedSqlAnnotation>> processed =
                new SqlAnnotationProcessor().process(parsed, entryPoints);

        String key = entryPoints.getFirst().methodSignatureKey();
        var sql = processed.get(key).getFirst();
        assertEquals(false, sql.dslPromotionWarning());
        assertEquals(null, sql.warningMessage());
    }

    @Test
    void skipsDslPromotionWarningForMalformedSqlWithUnclosedStringLiteral() throws Exception {
        Path sourceFile = tempDir.resolve("MalformedSqlStringEntryPoint.java");
        Files.writeString(sourceFile, """
                import titan.dsl.SQL;
                import titan.dsl.SqlDialect;
                import titan.dsl.StoredProcedure;

                class MalformedSqlStringEntryPoint {
                    @StoredProcedure
                    @SQL(dialect = SqlDialect.POSTGRESQL, value = "SELECT * FROM accounts WHERE status = 'active")
                    public static void run() {}
                }
                """);

        ParsedSources parsed = new JavaSourceParser().parse(List.of(sourceFile), List.of(), "21", false);
        List<DiscoveredEntryPoint> entryPoints = new EntryPointDiscovery().discover(parsed);

        Map<String, List<SqlAnnotationProcessor.ProcessedSqlAnnotation>> processed =
                new SqlAnnotationProcessor().process(parsed, entryPoints);

        String key = entryPoints.getFirst().methodSignatureKey();
        var sql = processed.get(key).getFirst();
        assertEquals(false, sql.dslPromotionWarning());
        assertEquals(null, sql.warningMessage());
    }
}
