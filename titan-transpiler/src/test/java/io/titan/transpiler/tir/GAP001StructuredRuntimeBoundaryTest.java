package io.titan.transpiler.tir;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import io.titan.transpiler.DiscoveredEntryPoint;
import io.titan.transpiler.EntryPointDiscovery;
import io.titan.transpiler.FeatureValidator;
import io.titan.transpiler.InternalHelperDiscovery;
import io.titan.transpiler.JavaSourceParser;
import io.titan.transpiler.ParsedSources;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GAP001StructuredRuntimeBoundaryTest {

    @TempDir
    Path tempDir;

    @Test
    void capturesLibraryDescriptorFixtureRecordConstructionBoundary() throws Exception {
        ParsedFixture fixture = parse("""
                import titan.dsl.StoredFunction;

                record FieldDescriptor(
                        String name,
                        String tableName,
                        String columnName,
                        String outputKey,
                        boolean listRoot,
                        String relationName,
                        String relatedRoot) {
                }

                record RootDescriptor(
                        String name,
                        String tableName,
                        String lookupColumn,
                        String requiredRole) {
                }

                class GAP001LibraryRuntimeFixture {
                    @StoredFunction
                    public static String resolveBookTitleOutputKey(String alias, boolean staff) {
                        FieldDescriptor title = new FieldDescriptor(
                                "title", "books", "title", alias, false, "", "");
                        FieldDescriptor author = new FieldDescriptor(
                                "author", "authors", "name", "authorName", false, "book_author", "author");
                        FieldDescriptor reviews = new FieldDescriptor(
                                "reviews", "reviews", "body", "reviews", true, "book_reviews", "review");
                        FieldDescriptor[] fields = new FieldDescriptor[]{title, author, reviews};
                        RootDescriptor book = new RootDescriptor("book", "books", "id", "staff");

                        if (!policyAllows(staff, book.requiredRole())) {
                            return "TITAN_VALIDATION_FORBIDDEN";
                        }
                        return outputKey(fieldByName(fields, "title"), alias);
                    }

                    static boolean policyAllows(boolean staff, String requiredRole) {
                        return staff || !"staff".equals(requiredRole);
                    }

                    static FieldDescriptor fieldByName(FieldDescriptor[] fields, String name) {
                        for (FieldDescriptor field : fields) {
                            if (field.name().equals(name)) {
                                return field;
                            }
                        }
                        throw new IllegalArgumentException("TITAN_VALIDATION_UNKNOWN_FIELD");
                    }

                    static String outputKey(FieldDescriptor field, String alias) {
                        if (alias != null && !alias.isBlank()) {
                            return alias;
                        }
                        return field.outputKey();
                    }
                }
                """);

        assertDoesNotThrow(() -> new FeatureValidator().validate(fixture.parsed(), fixture.entryPoints()));
    }

    @Test
    void allowsDescriptorArrayConstructionAndLengthValidation() throws Exception {
        ParsedFixture fixture = parse("""
                import titan.dsl.StoredFunction;

                record FieldDescriptor(String name, String outputKey) {
                }

                class GAP001ArrayBoundaryFixture {
                    @StoredFunction
                    public static int descriptorCount() {
                        FieldDescriptor[] fields = new FieldDescriptor[]{
                                null,
                                null
                        };
                        return fields.length;
                    }
                }
                """);

        assertDoesNotThrow(() -> new FeatureValidator().validate(fixture.parsed(), fixture.entryPoints()));
    }

    @Test
    void capturesMutableAliasArrayBoundary() throws Exception {
        ParsedFixture fixture = parse("""
                import titan.dsl.StoredFunction;

                class GAP001MutableAliasBoundaryFixture {
                    @StoredFunction
                    public static String mutateAlias(String[] aliases) {
                        aliases[0] = "title";
                        return aliases[0];
                    }
                }
                """);

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> new FeatureValidator().validate(fixture.parsed(), fixture.entryPoints())
        );

        assertTrue(exception.getMessage().contains("TITAN-E001"));
        assertTrue(exception.getMessage().contains("mutable array element assignment in transpiled code"));
    }

    @Test
    void rejectsPolymorphicDispatchBeforeLowering() throws Exception {
        ParsedFixture fixture = parse("""
                import titan.dsl.StoredFunction;

                interface LibraryPolicy {
                    boolean allows(String role, boolean staff);
                }

                class GAP001PolymorphicDispatchBoundaryFixture {
                    @StoredFunction
                    public static boolean canRead(LibraryPolicy policy, String role, boolean staff) {
                        return policy.allows(role, staff);
                    }
                }
                """);

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> new FeatureValidator().validate(fixture.parsed(), fixture.entryPoints())
        );

        assertTrue(exception.getMessage().contains("TITAN-E001"));
        assertTrue(exception.getMessage().contains("interface dispatch in transpiled code"));
        assertTrue(exception.getMessage().contains("closed-world monomorphic resolution"));
    }

    @Test
    void rejectsTypeVariableInterfaceDispatchBeforeLowering() throws Exception {
        ParsedFixture fixture = parse("""
                import titan.dsl.StoredFunction;

                interface LibraryPolicy {
                    boolean allows(String role, boolean staff);
                }

                class GAP001TypeVariableDispatchBoundaryFixture {
                    @StoredFunction
                    public static <T extends LibraryPolicy> boolean canRead(T policy, String role, boolean staff) {
                        return policy.allows(role, staff);
                    }
                }
                """);

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> new FeatureValidator().validate(fixture.parsed(), fixture.entryPoints())
        );

        assertTrue(exception.getMessage().contains("TITAN-E001"));
        assertTrue(exception.getMessage().contains("interface dispatch in transpiled code"));
    }

    @Test
    void rejectsConcreteInstanceDispatchBeforeLowering() throws Exception {
        ParsedFixture fixture = parse("""
                import titan.dsl.StoredFunction;

                final class LibraryFormatter {
                    String label(String name) {
                        return "book:" + name;
                    }
                }

                class GAP001ConcreteInstanceDispatchBoundaryFixture {
                    @StoredFunction
                    public static String label(LibraryFormatter formatter, String name) {
                        return formatter.label(name);
                    }
                }
                """);

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> new FeatureValidator().validate(fixture.parsed(), fixture.entryPoints())
        );

        assertTrue(exception.getMessage().contains("TITAN-E001"));
        assertTrue(exception.getMessage().contains("instance method call in transpiled code"));
        assertTrue(exception.getMessage().contains("closed-world monomorphic dispatch"));
    }

    @Test
    void rejectsEffectivelyFinalLocalInstanceDispatchBeforeLowering() throws Exception {
        ParsedFixture fixture = parse("""
                import titan.dsl.StoredFunction;

                final class LibraryFormatter {
                    String label(String name) {
                        return "book:" + name;
                    }
                }

                class GAP001EffectivelyFinalInstanceDispatchBoundaryFixture {
                    @StoredFunction
                    public static String label(LibraryFormatter formatter, String name) {
                        LibraryFormatter selected = formatter;
                        return selected.label(name);
                    }
                }
                """);

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> new FeatureValidator().validate(fixture.parsed(), fixture.entryPoints())
        );

        assertTrue(exception.getMessage().contains("TITAN-E001"));
        assertTrue(exception.getMessage().contains("instance method call in transpiled code"));
        assertTrue(exception.getMessage().contains("closed-world monomorphic dispatch"));
    }

    @Test
    void lowersStaticFinalInstanceHelperDispatchForBothDialects() throws Exception {
        Path source = write("""
                import titan.dsl.StoredFunction;

                final class LibraryFormatter {
                    String label(String name) {
                        return "book:" + name;
                    }
                }

                class GAP001StaticFinalInstanceDispatchBoundaryFixture {
                    static final LibraryFormatter FORMATTER = new LibraryFormatter();

                    @StoredFunction
                    public static String label(String name) {
                        LibraryFormatter selected = FORMATTER;
                        LibraryFormatter alias = selected;
                        return alias.label(name);
                    }
                }
                """);

        List<TranspilationPipeline.GeneratedSql> generated = new TranspilationPipeline().transpile(
                List.of(source),
                List.of(),
                List.of("postgresql", "mysql"),
                List.of("public"),
                true);

        String postgresRunSql = generated.stream()
                .filter(sql -> sql.target().equals("postgresql")
                        && sql.className().equals("GAP001StaticFinalInstanceDispatchBoundaryFixture"))
                .findFirst()
                .orElseThrow()
                .sql();
        String postgresHelperSql = generated.stream()
                .filter(sql -> sql.target().equals("postgresql") && sql.className().equals("LibraryFormatter"))
                .findFirst()
                .orElseThrow()
                .sql();
        String mysqlRunSql = generated.stream()
                .filter(sql -> sql.target().equals("mysql")
                        && sql.className().equals("GAP001StaticFinalInstanceDispatchBoundaryFixture"))
                .findFirst()
                .orElseThrow()
                .sql();

        assertTrue(postgresRunSql.contains("__titan_internal_library_formatter_label_"));
        assertTrue(postgresHelperSql.contains("'book:'"));
        assertTrue(postgresHelperSql.contains("p_name"));
        assertTrue(!postgresRunSql.contains("FORMATTER"));
        assertTrue(mysqlRunSql.contains("__titan_internal_library_formatter_label_"));
    }

    @Test
    void rejectsMonomorphicAdapterInterfaceDispatchBeforeLowering() throws Exception {
        ParsedFixture fixture = parse("""
                import titan.dsl.StoredFunction;

                interface FieldAdapter {
                    String outputKey(String fieldName);
                }

                final class TitleAdapter implements FieldAdapter {
                    public String outputKey(String fieldName) {
                        return "title".equals(fieldName) ? "title" : "unknown";
                    }
                }

                class GAP001MonomorphicAdapterDispatchBoundaryFixture {
                    @StoredFunction
                    public static String outputKey(TitleAdapter adapter, String fieldName) {
                        FieldAdapter selected = adapter;
                        return selected.outputKey(fieldName);
                    }
                }
                """);

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> new FeatureValidator().validate(fixture.parsed(), fixture.entryPoints())
        );

        assertTrue(exception.getMessage().contains("TITAN-E001"));
        assertTrue(exception.getMessage().contains("interface dispatch in transpiled code"));
        assertTrue(exception.getMessage().contains("closed-world monomorphic resolution"));
    }

    @Test
    void rejectsTwoImplementationAdapterInterfaceDispatchBeforeLowering() throws Exception {
        ParsedFixture fixture = parse("""
                import titan.dsl.StoredFunction;

                interface FieldAdapter {
                    String outputKey(String fieldName);
                }

                final class TitleAdapter implements FieldAdapter {
                    public String outputKey(String fieldName) {
                        return "title";
                    }
                }

                final class AuthorAdapter implements FieldAdapter {
                    public String outputKey(String fieldName) {
                        return "authorName";
                    }
                }

                class GAP001TwoImplementationAdapterDispatchBoundaryFixture {
                    @StoredFunction
                    public static String outputKey(boolean author, String fieldName) {
                        FieldAdapter selected = author ? new AuthorAdapter() : new TitleAdapter();
                        return selected.outputKey(fieldName);
                    }
                }
                """);

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> new FeatureValidator().validate(fixture.parsed(), fixture.entryPoints())
        );

        assertTrue(exception.getMessage().contains("TITAN-E001"));
        assertTrue(exception.getMessage().contains("interface dispatch in transpiled code"));
        assertTrue(exception.getMessage().contains("closed-world monomorphic resolution"));
    }

    @Test
    void capturesSourceLocalInstanceHelperDiscoveryBoundary() throws Exception {
        ParsedFixture fixture = parse("""
                import titan.dsl.StoredFunction;

                final class LibraryFormatter {
                    private final String prefix = "book:";

                    String label(String name) {
                        return prefix + name;
                    }
                }

                class GAP001InstanceHelperDiscoveryBoundaryFixture {
                    @StoredFunction
                    public static String label(LibraryFormatter formatter, String name) {
                        return formatter.label(name);
                    }
                }
                """);

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> new InternalHelperDiscovery().discover(fixture.parsed(), fixture.entryPoints())
        );

        assertTrue(exception.getMessage().contains("Titan internal instance helper method uses receiver state"));
        assertTrue(exception.getMessage().contains("LibraryFormatter.label"));
    }

    @Test
    void allowsScalarRecordAccessorValidation() throws Exception {
        ParsedFixture fixture = parse("""
                import titan.dsl.StoredFunction;

                record FieldDescriptor(String name, String outputKey) {
                }

                class GAP001RecordAccessorBoundaryFixture {
                    @StoredFunction
                    public static String outputKey(FieldDescriptor field) {
                        return field.outputKey();
                    }
                }
                """);

        assertDoesNotThrow(() -> new FeatureValidator().validate(fixture.parsed(), fixture.entryPoints()));
    }

    @Test
    void lowersScalarRecordConstructionAndAccessorsForPostgresAndMySql() throws Exception {
        Path source = write("""
                import titan.dsl.StoredFunction;

                record FieldDescriptor(String name, String outputKey, boolean listRoot) {
                }

                class GAP001RecordLoweringFixture {
                    @StoredFunction
                    public static String outputKey(String alias) {
                        FieldDescriptor field = descriptor("title", alias);
                        return outputKey(field);
                    }

                    static FieldDescriptor descriptor(String name, String alias) {
                        return new FieldDescriptor(name, alias, false);
                    }

                    static String outputKey(FieldDescriptor field) {
                        if (field.listRoot()) {
                            return "list";
                        }
                        return field.outputKey();
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
                .map(TranspilationPipeline.GeneratedSql::sql)
                .reduce("", (left, right) -> left + "\n" + right);
        String mysqlSql = generated.stream()
                .filter(sql -> sql.target().equals("mysql"))
                .map(TranspilationPipeline.GeneratedSql::sql)
                .reduce("", (left, right) -> left + "\n" + right);

        assertTrue(postgresSql.contains("v_field \"app\".\"__record_field_descriptor\";"));
        assertTrue(postgresSql.contains("CREATE OR REPLACE FUNCTION \"app\".\"__record_field_descriptor__new\"("));
        assertTrue(postgresSql.contains("CREATE OR REPLACE FUNCTION \"app\".\"__record_field_descriptor__output_key\"("));
        assertTrue(postgresSql.contains("\"app\".\"__record_field_descriptor__new\"(p_name, p_alias, FALSE)"));
        assertTrue(postgresSql.contains("\"app\".\"__record_field_descriptor__list_root\"(p_field)"));
        assertTrue(postgresSql.contains("\"app\".\"__record_field_descriptor__output_key\"(p_field)"));
        assertTrue(mysqlSql.contains("DECLARE v_field JSON;"));
        assertTrue(mysqlSql.contains("CREATE FUNCTION `app`.`__record_field_descriptor__new`("));
        assertTrue(mysqlSql.contains("CREATE FUNCTION `app`.`__record_field_descriptor__output_key`("));
        assertTrue(mysqlSql.contains("`app`.`__record_field_descriptor__new`(p_name, p_alias, FALSE)"));
        assertTrue(mysqlSql.contains("`app`.`__record_field_descriptor__list_root`(p_field)"));
        assertTrue(mysqlSql.contains("`app`.`__record_field_descriptor__output_key`(p_field)"));
    }

    @Test
    void lowersRecordArrayScanForPostgresAndMySql() throws Exception {
        Path source = write("""
                import titan.dsl.StoredFunction;

                record FieldDescriptor(String name, String outputKey, boolean listRoot) {
                }

                class GAP001RecordArrayLoweringFixture {
                    @StoredFunction
                    public static String findOutputKey(String name) {
                        FieldDescriptor[] fields = new FieldDescriptor[]{
                                new FieldDescriptor("title", "title", false),
                                new FieldDescriptor("author", "authorName", false)
                        };
                        if (fields.length < 1) {
                            throw new IllegalArgumentException("TITAN_VALIDATION_EMPTY_DESCRIPTOR_LIST");
                        }
                        FieldDescriptor first = fields[0];
                        if (first.name().equals(name)) {
                            return first.outputKey();
                        }
                        for (FieldDescriptor field : fields) {
                            if (field.name().equals(name)) {
                                return field.outputKey();
                            }
                        }
                        throw new IllegalArgumentException("TITAN_VALIDATION_UNKNOWN_FIELD");
                    }

                    @StoredFunction
                    public static String findOutputKeyFromImmutableList(String name) {
                        for (FieldDescriptor field : java.util.List.of(
                                new FieldDescriptor("title", "title", false),
                                new FieldDescriptor("author", "authorName", false))) {
                            if (field.name().equals(name)) {
                                return field.outputKey();
                            }
                        }
                        throw new IllegalArgumentException("TITAN_VALIDATION_UNKNOWN_FIELD");
                    }

                    @StoredFunction
                    public static String findOutputKeyFromLocalImmutableList(String name) {
                        java.util.List<FieldDescriptor> descriptors = java.util.List.of(
                                new FieldDescriptor("title", "title", false),
                                new FieldDescriptor("author", "authorName", false));
                        for (FieldDescriptor field : descriptors) {
                            if (field.name().equals(name)) {
                                return field.outputKey();
                            }
                        }
                        throw new IllegalArgumentException("TITAN_VALIDATION_UNKNOWN_FIELD");
                    }

                    @StoredFunction
                    public static String firstAlias(String[] aliases) {
                        return aliases[0];
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
                .map(TranspilationPipeline.GeneratedSql::sql)
                .reduce("", (left, right) -> left + "\n" + right);
        String mysqlSql = generated.stream()
                .filter(sql -> sql.target().equals("mysql"))
                .map(TranspilationPipeline.GeneratedSql::sql)
                .reduce("", (left, right) -> left + "\n" + right);

        assertTrue(postgresSql.contains("v_fields \"app\".\"__record_field_descriptor\"[];"));
        assertTrue(postgresSql.contains("v_descriptors \"app\".\"__record_field_descriptor\"[];"));
        assertTrue(postgresSql.contains("ARRAY[\"app\".\"__record_field_descriptor__new\"('title', 'title', FALSE), \"app\".\"__record_field_descriptor__new\"('author', 'authorName', FALSE)]::\"app\".\"__record_field_descriptor\"[]"));
        assertTrue(postgresSql.contains("COALESCE(array_length(v_fields, 1), 0)"));
        assertTrue(postgresSql.contains("(v_fields)[(0) + 1]"));
        assertTrue(postgresSql.contains("FOREACH v_field IN ARRAY v_fields LOOP"));
        assertTrue(postgresSql.contains("FOREACH v_field IN ARRAY v_descriptors LOOP"));
        assertTrue(mysqlSql.contains("DECLARE v_fields JSON;"));
        assertTrue(mysqlSql.contains("DECLARE v_descriptors JSON;"));
        assertTrue(mysqlSql.contains("JSON_ARRAY(`app`.`__record_field_descriptor__new`('title', 'title', FALSE), `app`.`__record_field_descriptor__new`('author', 'authorName', FALSE))"));
        assertTrue(mysqlSql.contains("COALESCE(JSON_LENGTH(v_fields), 0)"));
        assertTrue(mysqlSql.contains("JSON_EXTRACT(v_fields, CONCAT('$[', 0, ']'))"));
        assertTrue(mysqlSql.contains("JSON_UNQUOTE(JSON_EXTRACT(p_aliases, CONCAT('$[', 0, ']')))"));
        assertTrue(mysqlSql.contains("JSON_TABLE(JSON_ARRAY(`app`.`__record_field_descriptor__new`('title', 'title', FALSE), `app`.`__record_field_descriptor__new`('author', 'authorName', FALSE))"));
    }

    @Test
    void capturesModelFactoryBaselineAsRuntimeHelperLowering() throws Exception {
        Path source = write("""
                import titan.dsl.StoredFunction;

                record FieldDescriptor(
                        String name,
                        String tableName,
                        String columnName,
                        String outputKey,
                        boolean listRoot) {
                }

                class GAP001FactoryFixture {
                    @StoredFunction
                    public static String outputKey(String fieldName) {
                        FieldDescriptor field = fieldByName(bookFields(), fieldName);
                        return field.outputKey();
                    }

                    static FieldDescriptor[] bookFields() {
                        return new FieldDescriptor[]{
                                descriptor("title", "title", false),
                                descriptor("reviews", "reviews", true)
                        };
                    }

                    static FieldDescriptor descriptor(String name, String outputKey, boolean listRoot) {
                        return new FieldDescriptor(name, "books", "title", outputKey, listRoot);
                    }

                    static FieldDescriptor fieldByName(FieldDescriptor[] fields, String name) {
                        for (FieldDescriptor field : fields) {
                            if (field.name().equals(name)) {
                                return field;
                            }
                        }
                        throw new IllegalArgumentException("TITAN_VALIDATION_UNKNOWN_FIELD");
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
                .map(TranspilationPipeline.GeneratedSql::sql)
                .reduce("", (left, right) -> left + "\n" + right);
        String mysqlSql = generated.stream()
                .filter(sql -> sql.target().equals("mysql"))
                .map(TranspilationPipeline.GeneratedSql::sql)
                .reduce("", (left, right) -> left + "\n" + right);

        assertTrue(postgresSql.contains("__titan_internal_gap001_factory_fixture_book_fields_"));
        assertTrue(postgresSql.contains("__titan_internal_gap001_factory_fixture_descriptor_"));
        assertTrue(postgresSql.contains("\"app\".\"__record_field_descriptor__new\"(p_name, 'books', 'title', p_output_key, p_list_root)"));
        assertTrue(mysqlSql.contains("__titan_internal_gap001_factory_fixture_book_fields_"));
        assertTrue(mysqlSql.contains("__titan_internal_gap001_factory_fixture_descriptor_"));
        assertTrue(mysqlSql.contains("`app`.`__record_field_descriptor__new`(p_name, 'books', 'title', p_output_key, p_list_root)"));
    }

    @Test
    void rejectsRuntimeOnlyModelFactoryStructuralIdentifierInputBeforeLowering() throws Exception {
        Path source = write("""
                import titan.dsl.StoredFunction;

                record FieldDescriptor(String name, String tableName, String columnName, String outputKey) {
                }

                class GAP001RuntimeModelFactoryBoundaryFixture {
                    @StoredFunction
                    public static String tableName(String tenant) {
                        FieldDescriptor field = bookField(tenant);
                        return field.tableName();
                    }

                    static FieldDescriptor bookField(String tenant) {
                        return new FieldDescriptor("title", "book_" + tenant, "title", "title");
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
        assertTrue(exception.getMessage().contains("runtime-derived structural identifier record component 'tableName'"));
    }

    @Test
    void lowersSourceLocalStaticFinalStructuralIdentifierConstantsInModelFactories() throws Exception {
        Path source = write("""
                import titan.dsl.StoredFunction;

                record FieldDescriptor(String name, String tableName, String columnName, String outputKey) {
                }

                final class GAP001FactoryConstants {
                    static final String TITLE_COLUMN = "title";
                    static final String REVIEW_COLUMN = "body";
                }

                class GAP001ConstantFactoryFixture {
                    static final String BOOK_TABLE = "books";

                    @StoredFunction
                    public static String columnName(String fieldName) {
                        FieldDescriptor field = fieldByName(bookFields(), fieldName);
                        return field.columnName();
                    }

                    static FieldDescriptor[] bookFields() {
                        return new FieldDescriptor[]{
                                new FieldDescriptor("title", BOOK_TABLE, GAP001FactoryConstants.TITLE_COLUMN, "title"),
                                new FieldDescriptor("reviews", BOOK_TABLE, GAP001FactoryConstants.REVIEW_COLUMN, "reviews")
                        };
                    }

                    static FieldDescriptor fieldByName(FieldDescriptor[] fields, String name) {
                        for (FieldDescriptor field : fields) {
                            if (field.name().equals(name)) {
                                return field;
                            }
                        }
                        throw new IllegalArgumentException("TITAN_VALIDATION_UNKNOWN_FIELD");
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
                .map(TranspilationPipeline.GeneratedSql::sql)
                .reduce("", (left, right) -> left + "\n" + right);
        String mysqlSql = generated.stream()
                .filter(sql -> sql.target().equals("mysql"))
                .map(TranspilationPipeline.GeneratedSql::sql)
                .reduce("", (left, right) -> left + "\n" + right);

        assertTrue(postgresSql.contains("\"app\".\"__record_field_descriptor__new\"('title', 'books', 'title', 'title')"));
        assertTrue(postgresSql.contains("\"app\".\"__record_field_descriptor__new\"('reviews', 'books', 'body', 'reviews')"));
        assertTrue(mysqlSql.contains("`app`.`__record_field_descriptor__new`('title', 'books', 'title', 'title')"));
        assertTrue(mysqlSql.contains("`app`.`__record_field_descriptor__new`('reviews', 'books', 'body', 'reviews')"));
    }

    @Test
    void capturesQueryTemplatePlanningBaselineWithStaticDslBranches() throws Exception {
        Path source = write("""
                import titan.dsl.*;
                import static titan.dsl.DSL.*;

                record RootDescriptor(String name, String tableName, String lookupColumn, boolean listRoot) {
                }

                record FieldDescriptor(String name, String tableName, String columnName, String outputKey, boolean relationField) {
                }

                record RelationDescriptor(String name, String fromTable, String fromColumn, String toTable, String toColumn, boolean many) {
                }

                class GAP001QueryTemplatePlanFixture {
                    static final BooksTable BOOKS = new BooksTable();
                    static final ReviewsTable REVIEWS = new ReviewsTable();

                    @StoredProcedure
                    public static void loadBookPlan(String fieldName, Integer bookId, boolean staffOnly) {
                        RootDescriptor root = new RootDescriptor("book", "books", "id", false);
                        FieldDescriptor field = fieldByName(bookFields(), fieldName);
                        RelationDescriptor[] relations = new RelationDescriptor[]{
                                new RelationDescriptor("book_reviews", "books", "id", "reviews", "book_id", true)
                        };

                        if (staffOnly && root.lookupColumn().equals("id") && field.relationField() == false) {
                            select(BOOKS.TITLE)
                                    .from(BOOKS)
                                    .where(BOOKS.ID.eq(bookId))
                                    .fetchOne();
                            return;
                        }
                        if (field.relationField() && relationByName(relations, "book_reviews").many()) {
                            select(REVIEWS.BODY)
                                    .from(REVIEWS)
                                    .where(REVIEWS.BOOK_ID.eq(bookId))
                                    .fetch();
                        }
                    }

                    static FieldDescriptor[] bookFields() {
                        return new FieldDescriptor[]{
                                new FieldDescriptor("title", "books", "title", "title", false),
                                new FieldDescriptor("reviews", "reviews", "body", "reviews", true)
                        };
                    }

                    static FieldDescriptor fieldByName(FieldDescriptor[] fields, String name) {
                        for (FieldDescriptor field : fields) {
                            if (field.name().equals(name)) {
                                return field;
                            }
                        }
                        throw new IllegalArgumentException("TITAN_VALIDATION_UNKNOWN_FIELD");
                    }

                    static RelationDescriptor relationByName(RelationDescriptor[] relations, String name) {
                        for (RelationDescriptor relation : relations) {
                            if (relation.name().equals(name)) {
                                return relation;
                            }
                        }
                        throw new IllegalArgumentException("TITAN_VALIDATION_UNKNOWN_RELATION");
                    }

                    static final class BooksTable extends Table<Object> {
                        public final Column<Integer> ID = column("id", SQLType.INTEGER, Nullability.NOT_NULL);
                        public final Column<String> TITLE = column("title", SQLType.TEXT, Nullability.NULLABLE);

                        BooksTable() {
                            super("books", "public");
                        }
                    }

                    static final class ReviewsTable extends Table<Object> {
                        public final Column<Integer> BOOK_ID = column("book_id", SQLType.INTEGER, Nullability.NOT_NULL);
                        public final Column<String> BODY = column("body", SQLType.TEXT, Nullability.NULLABLE);

                        ReviewsTable() {
                            super("reviews", "public");
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
                .filter(sql -> sql.target().equals("postgresql"))
                .map(TranspilationPipeline.GeneratedSql::sql)
                .reduce("", (left, right) -> left + "\n" + right);
        String mysqlSql = generated.stream()
                .filter(sql -> sql.target().equals("mysql"))
                .map(TranspilationPipeline.GeneratedSql::sql)
                .reduce("", (left, right) -> left + "\n" + right);

        assertTrue(postgresSql.contains("__titan_internal_gap001_query_template_plan_fixture_bo_"));
        assertTrue(postgresSql.contains("__titan_internal_gap001_query_template_plan_fixture_re_"));
        assertTrue(postgresSql.contains("\"app\".\"__record_root_descriptor__new\"('book', 'books', 'id', FALSE)"));
        // G1 (spike B1): both selects are discarded (fetchOne()/fetch() as void-procedure
        // statements), so PostgreSQL lowers them to PERFORM; MySQL keeps the bare SELECT.
        assertTrue(postgresSql.contains("PERFORM \"books\".\"title\" FROM \"books\""));
        assertTrue(postgresSql.contains("\"books\".\"id\" = p_book_id"));
        assertTrue(postgresSql.contains("PERFORM \"reviews\".\"body\" FROM \"reviews\""));
        assertTrue(postgresSql.contains("\"reviews\".\"book_id\" = p_book_id"));
        // Plan 3.4: MySQL helper names truncate (hash suffix preserved) at the dialect's
        // 64-char identifier limit from NamingRules, mirroring PostgreSQL's 63-char truncation
        // above — the unbounded names previously emitted exceeded MySQL's routine-name limit.
        assertTrue(mysqlSql.contains("__titan_internal_gap001_query_template_plan_fixture_boo"));
        assertTrue(mysqlSql.contains("__titan_internal_gap001_query_template_plan_fixture_rel"));
        assertFalse(mysqlSql.contains("__titan_internal_gap001_query_template_plan_fixture_book_fields_"));
        assertTrue(mysqlSql.contains("`app`.`__record_root_descriptor__new`('book', 'books', 'id', FALSE)"));
        assertTrue(mysqlSql.contains("SELECT `books`.`title` FROM `books`"));
        assertTrue(mysqlSql.contains("`books`.`id` = p_book_id"));
        assertTrue(mysqlSql.contains("SELECT `reviews`.`body` FROM `reviews`"));
        assertTrue(mysqlSql.contains("`reviews`.`book_id` = p_book_id"));
    }

    @Test
    void capturesRowMaterializationRuntimeBaselineWithFetchIntoShapes() throws Exception {
        Path source = write("""
                import titan.dsl.*;
                import static titan.dsl.DSL.*;

                record BookRootRow(Integer id, String title) {
                }

                record ReviewChildRow(Integer bookId, String body) {
                }

                class GAP001RowMaterializationRuntimeFixture {
                    static final BooksTable BOOKS = new BooksTable();
                    static final ReviewsTable REVIEWS = new ReviewsTable();

                    @StoredProcedure
                    public static void materializeBookRows(Integer bookId) {
                        select(BOOKS.ID, BOOKS.TITLE)
                                .from(BOOKS)
                                .where(BOOKS.ID.eq(bookId))
                                .fetchInto(BookRootRow.class);

                        select(REVIEWS.BOOK_ID, REVIEWS.BODY)
                                .from(REVIEWS)
                                .where(REVIEWS.BOOK_ID.eq(bookId))
                                .fetchInto(ReviewChildRow.class);
                    }

                    static final class BooksTable extends Table<Object> {
                        public final Column<Integer> ID = column("id", SQLType.INTEGER, Nullability.NOT_NULL);
                        public final Column<String> TITLE = column("title", SQLType.TEXT, Nullability.NULLABLE);

                        BooksTable() {
                            super("books", "public");
                        }
                    }

                    static final class ReviewsTable extends Table<Object> {
                        public final Column<Integer> BOOK_ID = column("book_id", SQLType.INTEGER, Nullability.NOT_NULL);
                        public final Column<String> BODY = column("body", SQLType.TEXT, Nullability.NULLABLE);

                        ReviewsTable() {
                            super("reviews", "public");
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
                .filter(sql -> sql.target().equals("postgresql"))
                .map(TranspilationPipeline.GeneratedSql::sql)
                .reduce("", (left, right) -> left + "\n" + right);
        String mysqlSql = generated.stream()
                .filter(sql -> sql.target().equals("mysql"))
                .map(TranspilationPipeline.GeneratedSql::sql)
                .reduce("", (left, right) -> left + "\n" + right);

        // G1 (spike B1): these fetchInto(...) calls are discarded statements in a void procedure
        // (the materialized rows are not bound to anything), so PostgreSQL lowers each to PERFORM —
        // a bare SELECT raises "no destination for result data" at execute. A result-USING fetch
        // goes through the GAP-001 row-materialization plan, not ExecuteSqlStatement, so it is
        // unaffected. MySQL keeps the bare SELECT statement.
        assertTrue(postgresSql.contains("PERFORM \"books\".\"id\", \"books\".\"title\" FROM \"books\""));
        assertTrue(postgresSql.contains("\"books\".\"id\" = p_book_id"));
        assertTrue(postgresSql.contains("PERFORM \"reviews\".\"book_id\", \"reviews\".\"body\" FROM \"reviews\""));
        assertTrue(postgresSql.contains("\"reviews\".\"book_id\" = p_book_id"));
        assertTrue(mysqlSql.contains("SELECT `books`.`id`, `books`.`title` FROM `books`"));
        assertTrue(mysqlSql.contains("`books`.`id` = p_book_id"));
        assertTrue(mysqlSql.contains("SELECT `reviews`.`book_id`, `reviews`.`body` FROM `reviews`"));
        assertTrue(mysqlSql.contains("`reviews`.`book_id` = p_book_id"));
    }

    @Test
    void rejectsGroupedChildRowMaterializationBeforeStructuredOutputSupport() throws Exception {
        Path source = write("""
                import titan.dsl.*;
                import static titan.dsl.DSL.*;

                record ReviewChildRow(Integer bookId, String body) {
                }

                record BookWithReviewsRow(Integer id, String title, ReviewChildRow[] reviews) {
                }

                class GAP001GroupedRowMaterializationBoundaryFixture {
                    static final BooksTable BOOKS = new BooksTable();

                    @StoredProcedure
                    public static void materializeBookRows(Integer bookId) {
                        select(BOOKS.ID, BOOKS.TITLE, BOOKS.ID)
                                .from(BOOKS)
                                .where(BOOKS.ID.eq(bookId))
                                .fetchInto(BookWithReviewsRow.class);
                    }

                    static final class BooksTable extends Table<Object> {
                        public final Column<Integer> ID = column("id", SQLType.INTEGER, Nullability.NOT_NULL);
                        public final Column<String> TITLE = column("title", SQLType.TEXT, Nullability.NULLABLE);

                        BooksTable() {
                            super("books", "public");
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
        assertTrue(exception.getMessage().contains("fetchInto(BookWithReviewsRow.class)"));
        assertTrue(exception.getMessage().contains("nested row materialization"));
        assertTrue(exception.getMessage().contains("reviews"));
    }

    @Test
    void rejectsRuntimeSelectedQueryTemplateColumnIdentifierBeforeLowering() throws Exception {
        Path source = write("""
                import titan.dsl.StoredFunction;

                record FieldDescriptor(String name, String tableName, String columnName, String outputKey) {
                }

                class GAP001RuntimeColumnPlanBoundaryFixture {
                    @StoredFunction
                    public static String columnName(String requestedColumn) {
                        FieldDescriptor field = new FieldDescriptor("selected", "books", requestedColumn, "selected");
                        return field.columnName();
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
        assertTrue(exception.getMessage().contains("runtime-derived structural identifier record component 'columnName'"));
    }

    @Test
    void rejectsMutableStructuralIdentifierFieldInModelFactory() throws Exception {
        Path source = write("""
                import titan.dsl.StoredFunction;

                record FieldDescriptor(String name, String tableName, String columnName, String outputKey) {
                }

                class GAP001MutableConstantFactoryBoundaryFixture {
                    static String BOOK_TABLE = "books";

                    @StoredFunction
                    public static String tableName() {
                        FieldDescriptor field = new FieldDescriptor("title", BOOK_TABLE, "title", "title");
                        return field.tableName();
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
        assertTrue(exception.getMessage().contains("runtime-derived structural identifier record component 'tableName'"));
    }

    @Test
    void rejectsNonSourceLocalStructuralIdentifierConstantInModelFactory() throws Exception {
        Path classesDir = compileExternalConstant("""
                public final class GAP001ExternalConstants {
                    public static final String BOOK_TABLE = "books";
                }
                """);
        Path source = write("""
                import titan.dsl.StoredFunction;

                record FieldDescriptor(String name, String tableName, String columnName, String outputKey) {
                }

                class GAP001ExternalConstantFactoryBoundaryFixture {
                    @StoredFunction
                    public static String tableName() {
                        FieldDescriptor field = new FieldDescriptor("title", GAP001ExternalConstants.BOOK_TABLE, "title", "title");
                        return field.tableName();
                    }
                }
                """);

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> new TranspilationPipeline().transpile(
                        List.of(source),
                        withCurrentClasspath(classesDir),
                        List.of("postgresql", "mysql"),
                        List.of("app"),
                        true)
        );

        assertTrue(exception.getMessage().contains("TITAN-E001"));
        assertTrue(exception.getMessage().contains("runtime-derived structural identifier record component 'tableName'"));
    }

    @Test
    void rejectsNestedRecordComponentShapeBeforeLowering() throws Exception {
        ParsedFixture fixture = parse("""
                import titan.dsl.StoredFunction;

                record FieldDescriptor(String name) {
                }

                record RootDescriptor(FieldDescriptor title) {
                }

                class GAP001NestedRecordBoundaryFixture {
                    @StoredFunction
                    public static String rootName() {
                        RootDescriptor root = new RootDescriptor(new FieldDescriptor("title"));
                        return root.title().name();
                    }
                }
                """);

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> new FeatureValidator().validate(fixture.parsed(), fixture.entryPoints())
        );

        assertTrue(exception.getMessage().contains("TITAN-E001"));
        assertTrue(exception.getMessage().contains("only scalar record fields are supported"));
    }

    @Test
    void rejectsRecordArrayComponentShapeBeforeRecordDdlLowering() throws Exception {
        ParsedFixture fixture = parse("""
                import titan.dsl.StoredFunction;

                record FieldDescriptor(String name) {
                }

                record RootDescriptor(FieldDescriptor[] fields) {
                }

                class GAP001RecordArrayComponentBoundaryFixture {
                    @StoredFunction
                    public static int count() {
                        RootDescriptor root = new RootDescriptor(new FieldDescriptor[]{
                                new FieldDescriptor("title")
                        });
                        return root.fields().length;
                    }
                }
                """);

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> new FeatureValidator().validate(fixture.parsed(), fixture.entryPoints())
        );

        assertTrue(exception.getMessage().contains("TITAN-E001"));
        assertTrue(exception.getMessage().contains("only scalar record fields are supported"));
    }

    @Test
    void rejectsDimensionedArrayConstructionBeforeDefaultElementLowering() throws Exception {
        ParsedFixture fixture = parse("""
                import titan.dsl.StoredFunction;

                class GAP001DimensionedArrayBoundaryFixture {
                    @StoredFunction
                    public static int count() {
                        int[] values = new int[3];
                        return values.length;
                    }
                }
                """);

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> new FeatureValidator().validate(fixture.parsed(), fixture.entryPoints())
        );

        assertTrue(exception.getMessage().contains("TITAN-E001"));
        assertTrue(exception.getMessage().contains("dimensioned array construction in transpiled code"));
        assertTrue(exception.getMessage().contains("default element materialization"));
    }

    @Test
    void rejectsStructuredObjectFieldAccessBeforeLowering() throws Exception {
        ParsedFixture fixture = parse("""
                import titan.dsl.StoredFunction;

                final class FieldDescriptor {
                    String outputKey;
                }

                class GAP001FieldAccessBoundaryFixture {
                    @StoredFunction
                    public static String outputKey(FieldDescriptor field) {
                        return field.outputKey;
                    }
                }
                """);

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> new FeatureValidator().validate(fixture.parsed(), fixture.entryPoints())
        );

        assertTrue(exception.getMessage().contains("TITAN-E001"));
        assertTrue(exception.getMessage().contains("object field access in transpiled code"));
        assertTrue(exception.getMessage().contains("structured value support"));
    }

    @Test
    void allowsArrayLengthAccessValidation() throws Exception {
        ParsedFixture fixture = parse("""
                import titan.dsl.StoredFunction;

                class GAP001ArrayLengthBoundaryFixture {
                    @StoredFunction
                    public static int count(String[] aliases) {
                        return aliases.length;
                    }
                }
                """);

        assertDoesNotThrow(() -> new FeatureValidator().validate(fixture.parsed(), fixture.entryPoints()));
    }

    @Test
    void rejectsUnsupportedEntryPointArrayElementShapeBeforeLowering() throws Exception {
        ParsedFixture fixture = parse("""
                import titan.dsl.StoredFunction;

                class GAP001UnsupportedArrayParameterBoundaryFixture {
                    @StoredFunction
                    public static String first(Object[] values) {
                        return String.valueOf(values[0]);
                    }
                }
                """);

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> new FeatureValidator().validate(fixture.parsed(), fixture.entryPoints())
        );

        assertTrue(exception.getMessage().contains("TITAN-E001"));
        assertTrue(exception.getMessage().contains("entry-point array parameter element type"));
        assertTrue(exception.getMessage().contains("only scalar or record elements are supported"));
    }

    @Test
    void rejectsCollectionFactoryBeforeLowering() throws Exception {
        ParsedFixture fixture = parse("""
                import titan.dsl.StoredFunction;
                import java.util.List;

                class GAP001CollectionFactoryBoundaryFixture {
                    @StoredFunction
                    public static int descriptorCount() {
                        return List.of("title", "author").size();
                    }
                }
                """);

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> new FeatureValidator().validate(fixture.parsed(), fixture.entryPoints())
        );

        assertTrue(exception.getMessage().contains("TITAN-E001"));
        assertTrue(exception.getMessage().contains("collection operation in transpiled code"));
        assertTrue(exception.getMessage().contains("structured collection support"));
    }

    @Test
    void rejectsCollectionsFactoryBeforeLowering() throws Exception {
        ParsedFixture fixture = parse("""
                import titan.dsl.StoredFunction;
                import java.util.Collections;
                import java.util.List;

                class GAP001CollectionsFactoryBoundaryFixture {
                    @StoredFunction
                    public static List<String> descriptors() {
                        return Collections.emptyList();
                    }
                }
                """);

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> new FeatureValidator().validate(fixture.parsed(), fixture.entryPoints())
        );

        assertTrue(exception.getMessage().contains("TITAN-E001"));
        assertTrue(exception.getMessage().contains("collection operation in transpiled code"));
    }

    @Test
    void rejectsCollectionReceiverOperationBeforeLowering() throws Exception {
        ParsedFixture fixture = parse("""
                import titan.dsl.StoredFunction;
                import java.util.List;

                class GAP001CollectionReceiverBoundaryFixture {
                    @StoredFunction
                    public static String first(List<String> fields) {
                        return fields.get(0);
                    }
                }
                """);

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> new FeatureValidator().validate(fixture.parsed(), fixture.entryPoints())
        );

        assertTrue(exception.getMessage().contains("TITAN-E001"));
        assertTrue(exception.getMessage().contains("collection operation in transpiled code"));
    }

    @Test
    void rejectsTypeVariableConcreteInstanceDispatchBeforeLowering() throws Exception {
        ParsedFixture fixture = parse("""
                import titan.dsl.StoredFunction;

                class LibraryFormatter {
                    String label(String name) {
                        return "book:" + name;
                    }
                }

                class GAP001TypeVariableConcreteDispatchBoundaryFixture {
                    @StoredFunction
                    public static <T extends LibraryFormatter> String label(T formatter, String name) {
                        return formatter.label(name);
                    }
                }
                """);

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> new FeatureValidator().validate(fixture.parsed(), fixture.entryPoints())
        );

        assertTrue(exception.getMessage().contains("TITAN-E001"));
        assertTrue(exception.getMessage().contains("instance method call in transpiled code"));
    }

    @Test
    void rejectsReflectionBeforeLowering() throws Exception {
        ParsedFixture fixture = parse("""
                import titan.dsl.StoredFunction;

                class GAP001ReflectionBoundaryFixture {
                    @StoredFunction
                    public static String reflectedName(String className) throws ClassNotFoundException {
                        return Class.forName(className).getName();
                    }
                }
                """);

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> new FeatureValidator().validate(fixture.parsed(), fixture.entryPoints())
        );

        assertTrue(exception.getMessage().contains("TITAN-E001"));
        assertTrue(exception.getMessage().contains("reflection via Class.forName(...)"));
    }

    @Test
    void rejectsClassLiteralReflectionBeforeLowering() throws Exception {
        ParsedFixture fixture = parse("""
                import titan.dsl.StoredFunction;

                class GAP001ClassLiteralReflectionBoundaryFixture {
                    @StoredFunction
                    public static String reflectedName() {
                        return String.class.getName();
                    }
                }
                """);

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> new FeatureValidator().validate(fixture.parsed(), fixture.entryPoints())
        );

        assertTrue(exception.getMessage().contains("TITAN-E001"));
        assertTrue(exception.getMessage().contains("reflection via class literal"));
    }

    @Test
    void rejectsGetClassReflectionBeforeLowering() throws Exception {
        ParsedFixture fixture = parse("""
                import titan.dsl.StoredFunction;

                class GAP001GetClassReflectionBoundaryFixture {
                    @StoredFunction
                    public static String reflectedName(String value) {
                        return value.getClass().getName();
                    }
                }
                """);

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> new FeatureValidator().validate(fixture.parsed(), fixture.entryPoints())
        );

        assertTrue(exception.getMessage().contains("TITAN-E001"));
        assertTrue(exception.getMessage().contains("reflection via getClass()"));
    }

    @Test
    void rejectsStaticallyImportedReflectionBeforeLowering() throws Exception {
        ParsedFixture fixture = parse("""
                import titan.dsl.StoredFunction;
                import static java.lang.Class.forName;

                class GAP001StaticImportReflectionBoundaryFixture {
                    @StoredFunction
                    public static String reflectedName(String className) throws ClassNotFoundException {
                        return forName(className).getName();
                    }
                }
                """);

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> new FeatureValidator().validate(fixture.parsed(), fixture.entryPoints())
        );

        assertTrue(exception.getMessage().contains("TITAN-E001"));
        assertTrue(exception.getMessage().contains("reflection via Class.forName(...)"));
    }

    @Test
    void capturesRuntimeTableNameBlockedByStructuredValueBoundary() throws Exception {
        ParsedFixture fixture = parse("""
                import titan.dsl.StoredFunction;

                record RootDescriptor(String name, String tableName, String lookupColumn) {
                }

                class GAP001RuntimeTableBoundaryFixture {
                    @StoredFunction
                    public static String runtimeTable(String tenant) {
                        RootDescriptor root = new RootDescriptor("book", "book_" + tenant, "id");
                        return root.tableName();
                    }
                }
                """);

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> new FeatureValidator().validate(fixture.parsed(), fixture.entryPoints())
        );

        assertTrue(exception.getMessage().contains("TITAN-E001"));
        assertTrue(exception.getMessage().contains("runtime-derived structural identifier record component 'tableName'"));
    }

    private ParsedFixture parse(String source) throws Exception {
        Path sourceFile = write(source);

        ParsedSources parsed = new JavaSourceParser().parse(List.of(sourceFile), List.of(), "21", false);
        List<DiscoveredEntryPoint> entryPoints = new EntryPointDiscovery().discover(parsed);
        return new ParsedFixture(parsed, entryPoints);
    }

    private Path write(String source) throws Exception {
        Path sourceFile = tempDir.resolve("GAP001Fixture" + System.nanoTime() + ".java");
        Files.writeString(sourceFile, source);
        return sourceFile;
    }

    private Path compileExternalConstant(String source) throws Exception {
        Path sourceFile = tempDir.resolve("GAP001ExternalConstants.java");
        Path classesDir = tempDir.resolve("classes");
        Files.createDirectories(classesDir);
        Files.writeString(sourceFile, source);
        int result = javax.tools.ToolProvider.getSystemJavaCompiler().run(
                null,
                null,
                null,
                "-d",
                classesDir.toString(),
                sourceFile.toString());
        if (result != 0) {
            throw new AssertionError("failed to compile external constants fixture");
        }
        return classesDir;
    }

    private List<Path> withCurrentClasspath(Path firstEntry) {
        List<Path> classpath = new java.util.ArrayList<>();
        classpath.add(firstEntry);
        for (String entry : System.getProperty("java.class.path", "").split(java.io.File.pathSeparator)) {
            if (!entry.isBlank()) {
                classpath.add(Path.of(entry));
            }
        }
        return classpath;
    }

    private record ParsedFixture(ParsedSources parsed, List<DiscoveredEntryPoint> entryPoints) {
    }
}
