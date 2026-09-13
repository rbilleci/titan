package io.titan.transpiler.tir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import io.titan.transpiler.DiscoveredEntryPoint;
import io.titan.transpiler.EntryPointDiscovery;
import io.titan.transpiler.JavaSourceParser;
import io.titan.transpiler.ParsedSources;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JavaToTirLowererJoinOnColumnsTest {

    @TempDir
    Path tempDir;

    @Test
    void lowersDslJoinOnColumnPairHelperIntoEqualityCondition() throws Exception {
        Path sourceFile = tempDir.resolve("LoweringDslJoinOnColumns.java");
        Files.writeString(sourceFile, """
                import titan.dsl.*;
                import static titan.dsl.DSL.*;

                class LoweringDslJoinOnColumns {
                    static final AccountsTable ACCOUNTS = new AccountsTable();
                    static final PlansTable PLANS = new PlansTable();

                    @StoredProcedure
                    public static void run() {
                        select(ACCOUNTS.ID, PLANS.NAME)
                                .from(ACCOUNTS)
                                .join(PLANS).on(ACCOUNTS.PLAN_CODE, PLANS.CODE)
                                .where(ACCOUNTS.ACTIVE.eq(true))
                                .fetch();
                    }

                    static final class AccountsTable extends Table<Object> {
                        public final Column<Integer> ID = column("id", SQLType.INTEGER, Nullability.NOT_NULL);
                        public final Column<Boolean> ACTIVE = column("active", SQLType.BOOLEAN, Nullability.NULLABLE);
                        public final Column<String> PLAN_CODE = column("plan_code", SQLType.TEXT, Nullability.NULLABLE);

                        AccountsTable() {
                            super("accounts", "public");
                        }
                    }

                    static final class PlansTable extends Table<Object> {
                        public final Column<String> CODE = column("code", SQLType.TEXT, Nullability.NOT_NULL);
                        public final Column<String> NAME = column("name", SQLType.TEXT, Nullability.NOT_NULL);

                        PlansTable() {
                            super("plans", "public");
                        }
                    }
                }
                """);

        ParsedSources parsed = new JavaSourceParser().parse(List.of(sourceFile), List.of(), "21", false);
        List<DiscoveredEntryPoint> entryPoints = new EntryPointDiscovery().discover(parsed);

        var block = new JavaToTirLowerer().lower(parsed, entryPoints).values().iterator().next();
        var selectExec = assertInstanceOf(ExecuteSqlStatement.class, block.statements().getFirst());
        var select = assertInstanceOf(SelectSql.class, selectExec.sqlNode());

        assertEquals(1, select.joins().size());
        var join = select.joins().getFirst();
        var condition = assertInstanceOf(BinaryOpExpression.class, join.condition());
        assertEquals(BinaryOperator.EQUAL, condition.operator());
    }

    @Test
    void lowersDslJoinOnColumnPairWithExtraPredicateIntoAndCondition() throws Exception {
        Path sourceFile = tempDir.resolve("LoweringDslJoinOnColumnsWithExtraPredicate.java");
        Files.writeString(sourceFile, """
                import titan.dsl.*;
                import static titan.dsl.DSL.*;

                class LoweringDslJoinOnColumnsWithExtraPredicate {
                    static final AccountsTable ACCOUNTS = new AccountsTable();
                    static final PlansTable PLANS = new PlansTable();

                    @StoredProcedure
                    public static void run() {
                        select(ACCOUNTS.ID, PLANS.NAME)
                                .from(ACCOUNTS)
                                .leftJoin(PLANS).on(ACCOUNTS.PLAN_CODE, PLANS.CODE, PLANS.PAID.eq(true))
                                .where(ACCOUNTS.ACTIVE.eq(true))
                                .fetch();
                    }

                    static final class AccountsTable extends Table<Object> {
                        public final Column<Integer> ID = column("id", SQLType.INTEGER, Nullability.NOT_NULL);
                        public final Column<Boolean> ACTIVE = column("active", SQLType.BOOLEAN, Nullability.NULLABLE);
                        public final Column<String> PLAN_CODE = column("plan_code", SQLType.TEXT, Nullability.NULLABLE);

                        AccountsTable() {
                            super("accounts", "public");
                        }
                    }

                    static final class PlansTable extends Table<Object> {
                        public final Column<String> CODE = column("code", SQLType.TEXT, Nullability.NOT_NULL);
                        public final Column<String> NAME = column("name", SQLType.TEXT, Nullability.NOT_NULL);
                        public final Column<Boolean> PAID = column("paid", SQLType.BOOLEAN, Nullability.NOT_NULL);

                        PlansTable() {
                            super("plans", "public");
                        }
                    }
                }
                """);

        ParsedSources parsed = new JavaSourceParser().parse(List.of(sourceFile), List.of(), "21", false);
        List<DiscoveredEntryPoint> entryPoints = new EntryPointDiscovery().discover(parsed);

        var block = new JavaToTirLowerer().lower(parsed, entryPoints).values().iterator().next();
        var selectExec = assertInstanceOf(ExecuteSqlStatement.class, block.statements().getFirst());
        var select = assertInstanceOf(SelectSql.class, selectExec.sqlNode());

        assertEquals(1, select.joins().size());
        var join = select.joins().getFirst();
        var condition = assertInstanceOf(BinaryOpExpression.class, join.condition());
        assertEquals(BinaryOperator.AND, condition.operator());
        var left = assertInstanceOf(BinaryOpExpression.class, condition.left());
        assertEquals(BinaryOperator.EQUAL, left.operator());
        var right = assertInstanceOf(BinaryOpExpression.class, condition.right());
        assertEquals(BinaryOperator.EQUAL, right.operator());
    }
}
