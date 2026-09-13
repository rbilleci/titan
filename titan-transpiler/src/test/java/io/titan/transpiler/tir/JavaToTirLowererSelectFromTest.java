package io.titan.transpiler.tir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.titan.transpiler.DiscoveredEntryPoint;
import io.titan.transpiler.EntryPointDiscovery;
import io.titan.transpiler.JavaSourceParser;
import io.titan.transpiler.ParsedSources;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JavaToTirLowererSelectFromTest {

    @TempDir
    Path tempDir;

    @Test
    void lowersDslSelectFromTableIntoExplicitProjectionList() throws Exception {
        Path sourceFile = tempDir.resolve("LoweringDslSelectFromTable.java");
        Files.writeString(sourceFile, """
                import titan.dsl.*;
                import static titan.dsl.DSL.*;

                class LoweringDslSelectFromTable {
                    static final AccountsTable ACCOUNTS = new AccountsTable();

                    @StoredProcedure
                    public static void run() {
                        selectFrom(ACCOUNTS)
                                .where(ACCOUNTS.ACTIVE.eq(true))
                                .orderBy(ACCOUNTS.ID.asc())
                                .fetch();
                    }

                    static final class AccountsTable extends Table<Object> {
                        public final Column<Integer> ID = column("id", SQLType.INTEGER, Nullability.NOT_NULL);
                        public final Column<String> EMAIL = column("email", SQLType.TEXT, Nullability.NULLABLE);
                        public final Column<Boolean> ACTIVE = column("active", SQLType.BOOLEAN, Nullability.NULLABLE);

                        AccountsTable() {
                            super("accounts", "public");
                        }
                    }
                }
                """);

        ParsedSources parsed = new JavaSourceParser().parse(List.of(sourceFile), List.of(), "21", false);
        List<DiscoveredEntryPoint> entryPoints = new EntryPointDiscovery().discover(parsed);

        Block block = new JavaToTirLowerer().lower(parsed, entryPoints).values().iterator().next();
        ExecuteSqlStatement selectExec = assertInstanceOf(ExecuteSqlStatement.class, block.statements().getFirst());
        SelectSql select = assertInstanceOf(SelectSql.class, selectExec.sqlNode());

        assertEquals("accounts", select.from());
        assertEquals(3, select.columns().size());
        assertEquals("id", select.columns().get(0).alias());
        assertEquals("email", select.columns().get(1).alias());
        assertEquals("active", select.columns().get(2).alias());
    }

    @Test
    void lowersSelectFromWithRoutineParametersAndLikePredicate() throws Exception {
        Path sourceFile = tempDir.resolve("LoweringDslSelectFromWithParameters.java");
        Files.writeString(sourceFile, """
                import titan.dsl.*;
                import static titan.dsl.DSL.*;

                class LoweringDslSelectFromWithParameters {
                    static final OwnersTable OWNERS = new OwnersTable();

                    @StoredProcedure
                    public static void run(Integer ownerId, String lastNamePattern) {
                        selectFrom(OWNERS)
                                .where(OWNERS.ID.eq(ownerId).and(OWNERS.LAST_NAME.like(lastNamePattern)))
                                .fetch();
                    }

                    static final class OwnersTable extends Table<Object> {
                        public final Column<Integer> ID = column("id", SQLType.INTEGER, Nullability.NOT_NULL);
                        public final Column<String> LAST_NAME = column("last_name", SQLType.TEXT, Nullability.NULLABLE);

                        OwnersTable() {
                            super("owners", "public");
                        }
                    }
                }
                """);

        ParsedSources parsed = new JavaSourceParser().parse(List.of(sourceFile), List.of(), "21", false);
        List<DiscoveredEntryPoint> entryPoints = new EntryPointDiscovery().discover(parsed);

        Block block = new JavaToTirLowerer().lower(parsed, entryPoints).values().iterator().next();
        assertEquals(0, block.declarations().size());

        ExecuteSqlStatement selectExec = assertInstanceOf(ExecuteSqlStatement.class, block.statements().getFirst());
        SelectSql select = assertInstanceOf(SelectSql.class, selectExec.sqlNode());
        assertEquals("owners", select.from());

        CoalesceExpression nullableWhere = assertInstanceOf(CoalesceExpression.class, select.where());
        BinaryOpExpression andExpr = assertInstanceOf(BinaryOpExpression.class, nullableWhere.expressions().getFirst());
        assertEquals(BinaryOperator.AND, andExpr.operator());

        BinaryOpExpression ownerIdExpr = assertInstanceOf(BinaryOpExpression.class, andExpr.left());
        assertEquals(BinaryOperator.EQUAL, ownerIdExpr.operator());
        VariableRefExpression ownerIdRef = assertInstanceOf(VariableRefExpression.class, ownerIdExpr.right());
        assertEquals("ownerId", ownerIdRef.name());

        BinaryOpExpression likeExpr = assertInstanceOf(BinaryOpExpression.class, andExpr.right());
        assertEquals(BinaryOperator.LIKE, likeExpr.operator());
        VariableRefExpression lastNamePatternRef = assertInstanceOf(VariableRefExpression.class, likeExpr.right());
        assertEquals("lastNamePattern", lastNamePatternRef.name());
    }

    @Test
    void reportsRewriteGuidanceForQualifiedSelectFromTableReferences() throws Exception {
        Path sourceFile = tempDir.resolve("LoweringDslSelectFromQualifiedTable.java");
        Files.writeString(sourceFile, """
                import titan.dsl.*;
                import static titan.dsl.DSL.*;

                class LoweringDslSelectFromQualifiedTable {

                    @StoredProcedure
                    public static void run() {
                        selectFrom(CatalogTables.OWNERS)
                                .fetch();
                    }

                    static final class CatalogTables {
                        static final OwnersTable OWNERS = new OwnersTable();
                    }

                    static final class OwnersTable extends Table<Object> {
                        public final Column<Integer> ID = column("id", SQLType.INTEGER, Nullability.NOT_NULL);
                        public final Column<String> LAST_NAME = column("last_name", SQLType.TEXT, Nullability.NULLABLE);

                        OwnersTable() {
                            super("owners", "public");
                        }
                    }
                }
                """);

        ParsedSources parsed = new JavaSourceParser().parse(List.of(sourceFile), List.of(), "21", false);
        List<DiscoveredEntryPoint> entryPoints = new EntryPointDiscovery().discover(parsed);

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> new JavaToTirLowerer().lower(parsed, entryPoints));

        assertTrue(exception.getMessage().contains("qualified/generated table references"));
        assertTrue(exception.getMessage().contains("OwnersTable owners = CatalogTables.OWNERS; selectFrom(owners)"));
        assertTrue(exception.getMessage().contains("select(Foo.BAR.COL1, ...).from(Foo.BAR)"));
    }

    @Test
    void reportsWorkaroundWhenSelectFromUsesHelperMethodCall() throws Exception {
        Path sourceFile = tempDir.resolve("LoweringDslSelectFromHelperMethod.java");
        Files.writeString(sourceFile, """
                import titan.dsl.*;
                import static titan.dsl.DSL.*;

                class LoweringDslSelectFromHelperMethod {
                    @StoredProcedure
                    public static void run() {
                        selectFrom(ownersTable()).fetch();
                    }

                    static OwnersTable ownersTable() {
                        return new OwnersTable();
                    }

                    static final class OwnersTable extends Table<Object> {
                        public final Column<Integer> ID = column("id", SQLType.INTEGER, Nullability.NOT_NULL);

                        OwnersTable() {
                            super("owners", "public");
                        }
                    }
                }
                """);

        ParsedSources parsed = new JavaSourceParser().parse(List.of(sourceFile), List.of(), "21", false);
        List<DiscoveredEntryPoint> entryPoints = new EntryPointDiscovery().discover(parsed);

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> new JavaToTirLowerer().lower(parsed, entryPoints));

        assertTrue(exception.getMessage().contains("does not inspect helper method calls yet"));
        assertTrue(exception.getMessage().contains("Bind the returned table to a local variable of its concrete table type first"));
        assertTrue(exception.getMessage().contains("select(table.COL1, ...).from(table)"));
    }

    @Test
    void reportsWorkaroundWhenSelectFromAliasUsesGenericTableType() throws Exception {
        Path sourceFile = tempDir.resolve("LoweringDslSelectFromGenericTableAlias.java");
        Files.writeString(sourceFile, """
                import titan.dsl.*;
                import static titan.dsl.DSL.*;

                class LoweringDslSelectFromGenericTableAlias {
                    static final OwnersTable OWNERS = new OwnersTable();

                    @StoredProcedure
                    public static void run() {
                        Table<Object> owners = OWNERS;
                        selectFrom(owners).fetch();
                    }

                    static final class OwnersTable extends Table<Object> {
                        public final Column<Integer> ID = column("id", SQLType.INTEGER, Nullability.NOT_NULL);

                        OwnersTable() {
                            super("owners", "public");
                        }
                    }
                }
                """);

        ParsedSources parsed = new JavaSourceParser().parse(List.of(sourceFile), List.of(), "21", false);
        List<DiscoveredEntryPoint> entryPoints = new EntryPointDiscovery().discover(parsed);

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> new JavaToTirLowerer().lower(parsed, entryPoints));

        assertTrue(exception.getMessage().contains("variable declared as generic Table/TableLike"));
        assertTrue(exception.getMessage().contains("concrete generated type (or use var)"));
        assertTrue(exception.getMessage().contains("select(table.COL1, ...).from(table)"));
    }

    @Test
    void reportsWorkaroundWhenSelectFromTableHasNoPublicColumnFields() throws Exception {
        Path sourceFile = tempDir.resolve("LoweringDslSelectFromWithoutPublicColumns.java");
        Files.writeString(sourceFile, """
                import titan.dsl.*;
                import static titan.dsl.DSL.*;

                class LoweringDslSelectFromWithoutPublicColumns {
                    static final HiddenColumnsTable ACCOUNTS = new HiddenColumnsTable();

                    @StoredProcedure
                    public static void run() {
                        selectFrom(ACCOUNTS).fetch();
                    }

                    static final class HiddenColumnsTable extends Table<Object> {
                        private final Column<Integer> ID = column("id", SQLType.INTEGER, Nullability.NOT_NULL);
                        private final Column<String> EMAIL = column("email", SQLType.TEXT, Nullability.NULLABLE);

                        HiddenColumnsTable() {
                            super("accounts", "public");
                        }
                    }
                }
                """);

        ParsedSources parsed = new JavaSourceParser().parse(List.of(sourceFile), List.of(), "21", false);
        List<DiscoveredEntryPoint> entryPoints = new EntryPointDiscovery().discover(parsed);

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> new JavaToTirLowerer().lower(parsed, entryPoints));

        assertTrue(exception.getMessage().contains("could not discover any public Column fields"));
        assertTrue(exception.getMessage().contains("Expose public Column constants on the table type"));
        assertTrue(exception.getMessage().contains("select(table.COL1, ...).from(table)"));
    }
}
