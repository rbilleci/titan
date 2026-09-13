package io.titan.transpiler.tir.generative.equivalence.composition;

import io.titan.transpiler.tir.generative.shared.SelectCaseModel;
import java.util.List;

final class CompositionEquivalenceGenerator {

    CompositionEquivalenceCaseModel.CompositionEquivalenceCase generate(long seed) {
        CompositionEquivalenceCaseModel.Family family = switch ((int) Math.floorMod(seed, 7)) {
            case 0 -> CompositionEquivalenceCaseModel.Family.ACTIVE_ROWS_CTE_INLINE_VIEW;
            case 1 -> CompositionEquivalenceCaseModel.Family.NONNULL_EMAIL_CTE_INLINE_VIEW;
            case 2 -> CompositionEquivalenceCaseModel.Family.NULL_PLAN_CODE_CTE_INLINE_VIEW;
            case 3 -> CompositionEquivalenceCaseModel.Family.ACTIVE_ROWS_FIELD_CLASS_SQLTYPE;
            case 4 -> CompositionEquivalenceCaseModel.Family.PLANLESS_LOGIN_COUNT_FIELD_CLASS_SQLTYPE;
            case 5 -> CompositionEquivalenceCaseModel.Family.ACTIVE_NONNULL_EMAIL_TWO_HOP_CTE_INLINE_VIEW;
            default -> CompositionEquivalenceCaseModel.Family.ACTIVE_NONNULL_EMAIL_TWO_HOP_FIELD_CLASS_SQLTYPE;
        };
        return switch (family) {
            case ACTIVE_ROWS_CTE_INLINE_VIEW -> build(
                    family,
                    SelectCaseModel.selectCase(
                            CompositionEquivalenceProfile.CTE_INLINE_VIEW.id(),
                            SelectCaseModel.SourceTable.ACCOUNTS_FIXTURE,
                            projections(),
                            SelectCaseModel.compare(
                                    SelectCaseModel.column("active", SelectCaseModel.ValueType.BOOLEAN),
                                    SelectCaseModel.ComparisonOperator.EQ,
                                    SelectCaseModel.boolLiteral(true)),
                            List.of(SelectCaseModel.orderBy("id", SelectCaseModel.ValueType.INTEGER, SelectCaseModel.SortDirection.ASC)),
                            SelectCaseModel.ResultShape.ROW_SET),
                    "ACCOUNTS.ACTIVE.eq(true)",
                    "active_accounts");
            case NONNULL_EMAIL_CTE_INLINE_VIEW -> build(
                    family,
                    SelectCaseModel.selectCase(
                            CompositionEquivalenceProfile.CTE_INLINE_VIEW.id(),
                            SelectCaseModel.SourceTable.ACCOUNTS_FIXTURE,
                            projections(),
                            SelectCaseModel.nullCheck(
                                    SelectCaseModel.column("email", SelectCaseModel.ValueType.TEXT),
                                    SelectCaseModel.NullCheckKind.IS_NOT_NULL),
                            List.of(SelectCaseModel.orderBy("id", SelectCaseModel.ValueType.INTEGER, SelectCaseModel.SortDirection.ASC)),
                            SelectCaseModel.ResultShape.ROW_SET),
                    "ACCOUNTS.EMAIL.isNotNull()",
                    "email_accounts");
            case NULL_PLAN_CODE_CTE_INLINE_VIEW -> build(
                    family,
                    SelectCaseModel.selectCase(
                            CompositionEquivalenceProfile.CTE_INLINE_VIEW.id(),
                            SelectCaseModel.SourceTable.ACCOUNTS_FIXTURE,
                            projections(),
                            SelectCaseModel.nullCheck(
                                    SelectCaseModel.column("plan_code", SelectCaseModel.ValueType.TEXT),
                                    SelectCaseModel.NullCheckKind.IS_NULL),
                            List.of(SelectCaseModel.orderBy("id", SelectCaseModel.ValueType.INTEGER, SelectCaseModel.SortDirection.ASC)),
                            SelectCaseModel.ResultShape.ROW_SET),
                    "ACCOUNTS.PLAN_CODE.isNull()",
                    "planless_accounts");
            case ACTIVE_ROWS_FIELD_CLASS_SQLTYPE -> buildFieldOverloadEquivalence(
                    family,
                    SelectCaseModel.selectCase(
                            CompositionEquivalenceProfile.CTE_INLINE_VIEW.id(),
                            SelectCaseModel.SourceTable.ACCOUNTS_FIXTURE,
                            projections(),
                            SelectCaseModel.compare(
                                    SelectCaseModel.column("active", SelectCaseModel.ValueType.BOOLEAN),
                                    SelectCaseModel.ComparisonOperator.EQ,
                                    SelectCaseModel.boolLiteral(true)),
                            List.of(SelectCaseModel.orderBy("id", SelectCaseModel.ValueType.INTEGER, SelectCaseModel.SortDirection.ASC)),
                            SelectCaseModel.ResultShape.ROW_SET),
                    "ACCOUNTS.ACTIVE.eq(true)",
                    "active_accounts_typed",
                    fieldProjectionSpec("id", Integer.class, "INTEGER", "NOT_NULL"),
                    fieldProjectionSpec("email", String.class, "TEXT", "NULLABLE"));
            case PLANLESS_LOGIN_COUNT_FIELD_CLASS_SQLTYPE -> buildFieldOverloadEquivalence(
                    family,
                    SelectCaseModel.selectCase(
                            CompositionEquivalenceProfile.CTE_INLINE_VIEW.id(),
                            SelectCaseModel.SourceTable.ACCOUNTS_FIXTURE,
                            List.of(
                                    SelectCaseModel.projection("id", SelectCaseModel.column("id", SelectCaseModel.ValueType.INTEGER)),
                                    SelectCaseModel.projection("login_count", SelectCaseModel.column("login_count", SelectCaseModel.ValueType.INTEGER))),
                            SelectCaseModel.nullCheck(
                                    SelectCaseModel.column("plan_code", SelectCaseModel.ValueType.TEXT),
                                    SelectCaseModel.NullCheckKind.IS_NULL),
                            List.of(SelectCaseModel.orderBy("id", SelectCaseModel.ValueType.INTEGER, SelectCaseModel.SortDirection.ASC)),
                            SelectCaseModel.ResultShape.ROW_SET),
                    "ACCOUNTS.PLAN_CODE.isNull()",
                    "planless_accounts_typed",
                    fieldProjectionSpec("id", Integer.class, "INTEGER", "NOT_NULL"),
                    fieldProjectionSpec("login_count", Integer.class, "INTEGER", "NULLABLE"));
            case ACTIVE_NONNULL_EMAIL_TWO_HOP_CTE_INLINE_VIEW -> buildTwoHopCompositionEquivalence(
                    family,
                    SelectCaseModel.selectCase(
                            CompositionEquivalenceProfile.CTE_INLINE_VIEW.id(),
                            SelectCaseModel.SourceTable.ACCOUNTS_FIXTURE,
                            List.of(
                                    SelectCaseModel.projection("id", SelectCaseModel.column("id", SelectCaseModel.ValueType.INTEGER)),
                                    SelectCaseModel.projection("email", SelectCaseModel.column("email", SelectCaseModel.ValueType.TEXT))),
                            SelectCaseModel.and(
                                    SelectCaseModel.compare(
                                            SelectCaseModel.column("active", SelectCaseModel.ValueType.BOOLEAN),
                                            SelectCaseModel.ComparisonOperator.EQ,
                                            SelectCaseModel.boolLiteral(true)),
                                    SelectCaseModel.nullCheck(
                                            SelectCaseModel.column("email", SelectCaseModel.ValueType.TEXT),
                                            SelectCaseModel.NullCheckKind.IS_NOT_NULL)),
                            List.of(SelectCaseModel.orderBy("id", SelectCaseModel.ValueType.INTEGER, SelectCaseModel.SortDirection.ASC)),
                            SelectCaseModel.ResultShape.ROW_SET),
                    "active_accounts_multi",
                    "email_accounts_multi");
            case ACTIVE_NONNULL_EMAIL_TWO_HOP_FIELD_CLASS_SQLTYPE -> buildTwoHopFieldOverloadEquivalence(
                    family,
                    SelectCaseModel.selectCase(
                            CompositionEquivalenceProfile.CTE_INLINE_VIEW.id(),
                            SelectCaseModel.SourceTable.ACCOUNTS_FIXTURE,
                            List.of(
                                    SelectCaseModel.projection("id", SelectCaseModel.column("id", SelectCaseModel.ValueType.INTEGER)),
                                    SelectCaseModel.projection("email", SelectCaseModel.column("email", SelectCaseModel.ValueType.TEXT))),
                            SelectCaseModel.and(
                                    SelectCaseModel.compare(
                                            SelectCaseModel.column("active", SelectCaseModel.ValueType.BOOLEAN),
                                            SelectCaseModel.ComparisonOperator.EQ,
                                            SelectCaseModel.boolLiteral(true)),
                                    SelectCaseModel.nullCheck(
                                            SelectCaseModel.column("email", SelectCaseModel.ValueType.TEXT),
                                            SelectCaseModel.NullCheckKind.IS_NOT_NULL)),
                            List.of(SelectCaseModel.orderBy("id", SelectCaseModel.ValueType.INTEGER, SelectCaseModel.SortDirection.ASC)),
                            SelectCaseModel.ResultShape.ROW_SET),
                    "active_accounts_typed_multi",
                    "email_accounts_typed_multi");
        };
    }

    private static CompositionEquivalenceCaseModel.CompositionEquivalenceCase build(
            CompositionEquivalenceCaseModel.Family family,
            SelectCaseModel.SelectCase referenceCase,
            String whereCondition,
            String relationName
    ) {
        return CompositionEquivalenceCaseModel.of(
                CompositionEquivalenceProfile.CTE_INLINE_VIEW.id(),
                family,
                referenceCase,
                renderNamedCteSource(whereCondition, relationName),
                renderInlineViewSource(whereCondition, relationName));
    }

    private static CompositionEquivalenceCaseModel.CompositionEquivalenceCase buildFieldOverloadEquivalence(
            CompositionEquivalenceCaseModel.Family family,
            SelectCaseModel.SelectCase referenceCase,
            String whereCondition,
            String relationName,
            FieldProjectionSpec firstProjection,
            FieldProjectionSpec secondProjection
    ) {
        return CompositionEquivalenceCaseModel.of(
                CompositionEquivalenceProfile.CTE_INLINE_VIEW.id(),
                family,
                referenceCase,
                renderSource(whereCondition, relationName, true, true, firstProjection, secondProjection),
                renderSource(whereCondition, relationName, false, false, firstProjection, secondProjection));
    }

    private static List<SelectCaseModel.Projection> projections() {
        return List.of(
                SelectCaseModel.projection("id", SelectCaseModel.column("id", SelectCaseModel.ValueType.INTEGER)),
                SelectCaseModel.projection("email", SelectCaseModel.column("email", SelectCaseModel.ValueType.TEXT)));
    }

    private static CompositionEquivalenceCaseModel.CompositionEquivalenceCase buildTwoHopCompositionEquivalence(
            CompositionEquivalenceCaseModel.Family family,
            SelectCaseModel.SelectCase referenceCase,
            String firstRelationName,
            String secondRelationName
    ) {
        return CompositionEquivalenceCaseModel.of(
                CompositionEquivalenceProfile.CTE_INLINE_VIEW.id(),
                family,
                referenceCase,
                renderTwoHopNamedCteSource(firstRelationName, secondRelationName),
                renderTwoHopInlineViewSource(firstRelationName, secondRelationName));
    }

    private static CompositionEquivalenceCaseModel.CompositionEquivalenceCase buildTwoHopFieldOverloadEquivalence(
            CompositionEquivalenceCaseModel.Family family,
            SelectCaseModel.SelectCase referenceCase,
            String firstRelationName,
            String secondRelationName
    ) {
        return CompositionEquivalenceCaseModel.of(
                CompositionEquivalenceProfile.CTE_INLINE_VIEW.id(),
                family,
                referenceCase,
                renderTwoHopTypedFieldSource(firstRelationName, secondRelationName, true),
                renderTwoHopTypedFieldSource(firstRelationName, secondRelationName, false));
    }

    private static String renderNamedCteSource(String whereCondition, String relationName) {
        return renderSource(
                whereCondition,
                relationName,
                true,
                false,
                fieldProjectionSpec("id", Integer.class, "INTEGER", "NOT_NULL"),
                fieldProjectionSpec("email", String.class, "TEXT", "NULLABLE"));
    }

    private static String renderTwoHopNamedCteSource(String firstRelationName, String secondRelationName) {
        return """
                import titan.dsl.*;
                import static titan.dsl.DSL.*;

                class CompositionEquivalenceGenerated {
                    static final AccountsTable ACCOUNTS = new AccountsTable();

                    @StoredProcedure
                    static void run() {
                        CommonTableExpression<Object> %1$s = DSL.name("%1$s")
                                .as(select(ACCOUNTS.ID, ACCOUNTS.EMAIL)
                                        .from(ACCOUNTS)
                                        .where(ACCOUNTS.ACTIVE.eq(true)));
                        Column<Integer> activeId = %1$s.field("id", SQLType.INTEGER, Nullability.NOT_NULL);
                        Column<String> activeEmail = %1$s.field("email", SQLType.TEXT, Nullability.NULLABLE);
                        CommonTableExpression<Object> %2$s = DSL.name("%2$s")
                                .as(select(activeId, activeEmail)
                                        .from(%1$s)
                                        .where(activeEmail.isNotNull()));
                        Column<Integer> projectedId = %2$s.field("id", SQLType.INTEGER, Nullability.NOT_NULL);
                        Column<String> projectedEmail = %2$s.field("email", SQLType.TEXT, Nullability.NULLABLE);
                        DSL.with(%1$s, %2$s)
                                .select(projectedId, projectedEmail)
                                .from(%2$s)
                                .orderBy(projectedId.asc())
                                .fetch();
                    }

                    static final class AccountsTable extends Table<Object> {
                        public final Column<Integer> ID = column("id", SQLType.INTEGER, Nullability.NOT_NULL);
                        public final Column<String> EMAIL = column("email", SQLType.TEXT, Nullability.NULLABLE);
                        public final Column<Boolean> ACTIVE = column("active", SQLType.BOOLEAN, Nullability.NULLABLE);
                        public final Column<String> PLAN_CODE = column("plan_code", SQLType.TEXT, Nullability.NULLABLE);
                        public final Column<Integer> LOGIN_COUNT = column("login_count", SQLType.INTEGER, Nullability.NULLABLE);

                        AccountsTable() {
                            super("accounts", "public");
                        }
                    }
                }
                """.formatted(firstRelationName, secondRelationName);
    }

    private static String renderInlineViewSource(String whereCondition, String relationName) {
        return renderSource(
                whereCondition,
                relationName,
                false,
                false,
                fieldProjectionSpec("id", Integer.class, "INTEGER", "NOT_NULL"),
                fieldProjectionSpec("email", String.class, "TEXT", "NULLABLE"));
    }

    private static String renderTwoHopInlineViewSource(String firstRelationName, String secondRelationName) {
        return """
                import titan.dsl.*;
                import static titan.dsl.DSL.*;

                class CompositionEquivalenceGenerated {
                    static final AccountsTable ACCOUNTS = new AccountsTable();

                    @StoredProcedure
                    static void run() {
                        InlineView<Object> %1$s = DSL.defineInlineView(
                                select(ACCOUNTS.ID, ACCOUNTS.EMAIL)
                                        .from(ACCOUNTS)
                                        .where(ACCOUNTS.ACTIVE.eq(true)));
                        Column<Integer> activeId = %1$s.field("id", SQLType.INTEGER, Nullability.NOT_NULL);
                        Column<String> activeEmail = %1$s.field("email", SQLType.TEXT, Nullability.NULLABLE);
                        InlineView<Object> %2$s = DSL.defineInlineView(
                                select(activeId, activeEmail)
                                        .from(%1$s)
                                        .where(activeEmail.isNotNull()));
                        Column<Integer> projectedId = %2$s.field("id", SQLType.INTEGER, Nullability.NOT_NULL);
                        Column<String> projectedEmail = %2$s.field("email", SQLType.TEXT, Nullability.NULLABLE);
                        select(projectedId, projectedEmail)
                                .from(%2$s)
                                .orderBy(projectedId.asc())
                                .fetch();
                    }

                    static final class AccountsTable extends Table<Object> {
                        public final Column<Integer> ID = column("id", SQLType.INTEGER, Nullability.NOT_NULL);
                        public final Column<String> EMAIL = column("email", SQLType.TEXT, Nullability.NULLABLE);
                        public final Column<Boolean> ACTIVE = column("active", SQLType.BOOLEAN, Nullability.NULLABLE);
                        public final Column<String> PLAN_CODE = column("plan_code", SQLType.TEXT, Nullability.NULLABLE);
                        public final Column<Integer> LOGIN_COUNT = column("login_count", SQLType.INTEGER, Nullability.NULLABLE);

                        AccountsTable() {
                            super("accounts", "public");
                        }
                    }
                }
                """.formatted(firstRelationName, secondRelationName);
    }

    private static String renderTwoHopTypedFieldSource(String firstRelationName, String secondRelationName, boolean typedFieldOverload) {
        String secondProjectedId = typedFieldOverload
                ? "Column<Integer> projectedId = " + secondRelationName + ".field(\"id\", Integer.class);"
                : "Column<Integer> projectedId = " + secondRelationName + ".field(\"id\", SQLType.INTEGER, Nullability.NOT_NULL);";
        String secondProjectedEmail = typedFieldOverload
                ? "Column<String> projectedEmail = " + secondRelationName + ".field(\"email\", String.class);"
                : "Column<String> projectedEmail = " + secondRelationName + ".field(\"email\", SQLType.TEXT, Nullability.NULLABLE);";
        return """
                import titan.dsl.*;
                import static titan.dsl.DSL.*;

                class CompositionEquivalenceGenerated {
                    static final AccountsTable ACCOUNTS = new AccountsTable();

                    @StoredProcedure
                    static void run() {
                        CommonTableExpression<Object> %1$s = DSL.name("%1$s")
                                .as(select(ACCOUNTS.ID, ACCOUNTS.EMAIL)
                                        .from(ACCOUNTS)
                                        .where(ACCOUNTS.ACTIVE.eq(true)));
                        Column<Integer> activeId = %1$s.field("id", SQLType.INTEGER, Nullability.NOT_NULL);
                        Column<String> activeEmail = %1$s.field("email", SQLType.TEXT, Nullability.NULLABLE);
                        CommonTableExpression<Object> %2$s = DSL.name("%2$s")
                                .as(select(activeId, activeEmail)
                                        .from(%1$s)
                                        .where(activeEmail.isNotNull()));
                        %3$s
                        %4$s
                        DSL.with(%1$s, %2$s)
                                .select(projectedId, projectedEmail)
                                .from(%2$s)
                                .orderBy(projectedId.asc())
                                .fetch();
                    }

                    static final class AccountsTable extends Table<Object> {
                        public final Column<Integer> ID = column("id", SQLType.INTEGER, Nullability.NOT_NULL);
                        public final Column<String> EMAIL = column("email", SQLType.TEXT, Nullability.NULLABLE);
                        public final Column<Boolean> ACTIVE = column("active", SQLType.BOOLEAN, Nullability.NULLABLE);
                        public final Column<String> PLAN_CODE = column("plan_code", SQLType.TEXT, Nullability.NULLABLE);
                        public final Column<Integer> LOGIN_COUNT = column("login_count", SQLType.INTEGER, Nullability.NULLABLE);

                        AccountsTable() {
                            super("accounts", "public");
                        }
                    }
                }
                """.formatted(firstRelationName, secondRelationName, secondProjectedId, secondProjectedEmail);
    }

    private static String renderSource(
            String whereCondition,
            String relationName,
            boolean namedCte,
            boolean typedFieldOverload,
            FieldProjectionSpec firstProjection,
            FieldProjectionSpec secondProjection
    ) {
        String definition = namedCte
                ? "CommonTableExpression<Object> %1$s = DSL.name(\"%1$s\").as(%2$s);".formatted(relationName, relationSelectSql(whereCondition, firstProjection, secondProjection))
                : "InlineView<Object> %1$s = DSL.defineInlineView(%2$s);".formatted(relationName, relationSelectSql(whereCondition, firstProjection, secondProjection));
        String projectedFirst = typedFieldOverload
                ? "Column<%s> projectedFirst = %s.field(\"%s\", %s.class);".formatted(firstProjection.javaTypeName(), relationName, firstProjection.columnName(), firstProjection.javaTypeName())
                : "Column<%s> projectedFirst = %s.field(\"%s\", SQLType.%s, Nullability.%s);".formatted(firstProjection.javaTypeName(), relationName, firstProjection.columnName(), firstProjection.sqlTypeName(), firstProjection.nullabilityName());
        String projectedSecond = typedFieldOverload
                ? "Column<%s> projectedSecond = %s.field(\"%s\", %s.class);".formatted(secondProjection.javaTypeName(), relationName, secondProjection.columnName(), secondProjection.javaTypeName())
                : "Column<%s> projectedSecond = %s.field(\"%s\", SQLType.%s, Nullability.%s);".formatted(secondProjection.javaTypeName(), relationName, secondProjection.columnName(), secondProjection.sqlTypeName(), secondProjection.nullabilityName());
        return """
                import titan.dsl.*;
                import static titan.dsl.DSL.*;

                class CompositionEquivalenceGenerated {
                    static final AccountsTable ACCOUNTS = new AccountsTable();

                    @StoredProcedure
                    static void run() {
                        %s
                        %s
                        %s
                        %s
                                .from(%s)
                                .orderBy(projectedFirst.asc())
                                .fetch();
                    }

                    static final class AccountsTable extends Table<Object> {
                        public final Column<Integer> ID = column("id", SQLType.INTEGER, Nullability.NOT_NULL);
                        public final Column<String> EMAIL = column("email", SQLType.TEXT, Nullability.NULLABLE);
                        public final Column<Boolean> ACTIVE = column("active", SQLType.BOOLEAN, Nullability.NULLABLE);
                        public final Column<String> PLAN_CODE = column("plan_code", SQLType.TEXT, Nullability.NULLABLE);
                        public final Column<Integer> LOGIN_COUNT = column("login_count", SQLType.INTEGER, Nullability.NULLABLE);

                        AccountsTable() {
                            super("accounts", "public");
                        }
                    }
                }
                """.formatted(
                definition,
                projectedFirst,
                projectedSecond,
                namedCte
                        ? "DSL.with(" + relationName + ").select(projectedFirst, projectedSecond)"
                        : "select(projectedFirst, projectedSecond)",
                relationName);
    }

    private static String relationSelectSql(String whereCondition, FieldProjectionSpec firstProjection, FieldProjectionSpec secondProjection) {
        return "select(ACCOUNTS.%s, ACCOUNTS.%s).from(ACCOUNTS).where(%s)"
                .formatted(toColumnConstant(firstProjection.columnName()), toColumnConstant(secondProjection.columnName()), whereCondition);
    }

    private static String toColumnConstant(String columnName) {
        return switch (columnName) {
            case "id" -> "ID";
            case "email" -> "EMAIL";
            case "login_count" -> "LOGIN_COUNT";
            default -> throw new IllegalArgumentException("Unsupported projection column: " + columnName);
        };
    }

    private static FieldProjectionSpec fieldProjectionSpec(String columnName, Class<?> javaType, String sqlTypeName, String nullabilityName) {
        return new FieldProjectionSpec(columnName, javaType.getSimpleName(), sqlTypeName, nullabilityName);
    }

    private record FieldProjectionSpec(String columnName, String javaTypeName, String sqlTypeName, String nullabilityName) {}
}
