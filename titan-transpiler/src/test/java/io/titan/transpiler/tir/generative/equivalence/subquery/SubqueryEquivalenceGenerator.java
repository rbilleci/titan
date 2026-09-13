package io.titan.transpiler.tir.generative.equivalence.subquery;

import io.titan.transpiler.tir.generative.shared.SelectCaseModel;
import java.util.List;

final class SubqueryEquivalenceGenerator {

    SubqueryEquivalenceCaseModel.SubqueryEquivalenceCase generate(long seed) {
        SubqueryEquivalenceCaseModel.Family family = switch ((int) Math.floorMod(seed, 4)) {
            case 0 -> SubqueryEquivalenceCaseModel.Family.CORRELATED_EXISTS_PLANCODE_NONNULL;
            case 1 -> SubqueryEquivalenceCaseModel.Family.CORRELATED_NOT_EXISTS_PLANCODE_NULL;
            case 2 -> SubqueryEquivalenceCaseModel.Family.CORRELATED_SCALAR_SELF_EMAIL_NONNULL;
            default -> SubqueryEquivalenceCaseModel.Family.CORRELATED_EXISTS_SELF_ID_EMAIL_NONNULL_JOIN;
        };
        return switch (family) {
            case CORRELATED_EXISTS_PLANCODE_NONNULL -> build(
                    family,
                    SelectCaseModel.selectCase(
                            SubqueryEquivalenceProfile.SUBQUERY_FORMS.id(),
                            SelectCaseModel.SourceTable.ACCOUNTS_FIXTURE,
                            projections(),
                            SelectCaseModel.and(
                                    SelectCaseModel.compare(
                                            SelectCaseModel.column("active", SelectCaseModel.ValueType.BOOLEAN),
                                            SelectCaseModel.ComparisonOperator.EQ,
                                            SelectCaseModel.boolLiteral(true)),
                                    SelectCaseModel.nullCheck(
                                            SelectCaseModel.column("plan_code", SelectCaseModel.ValueType.TEXT),
                                            SelectCaseModel.NullCheckKind.IS_NOT_NULL)),
                            ordering(),
                            SelectCaseModel.ResultShape.ROW_SET),
                    renderExistsSource(),
                    renderNullCheckSource(false));
            case CORRELATED_NOT_EXISTS_PLANCODE_NULL -> build(
                    family,
                    SelectCaseModel.selectCase(
                            SubqueryEquivalenceProfile.SUBQUERY_FORMS.id(),
                            SelectCaseModel.SourceTable.ACCOUNTS_FIXTURE,
                            projections(),
                            SelectCaseModel.and(
                                    SelectCaseModel.compare(
                                            SelectCaseModel.column("active", SelectCaseModel.ValueType.BOOLEAN),
                                            SelectCaseModel.ComparisonOperator.EQ,
                                            SelectCaseModel.boolLiteral(true)),
                                    SelectCaseModel.nullCheck(
                                            SelectCaseModel.column("plan_code", SelectCaseModel.ValueType.TEXT),
                                            SelectCaseModel.NullCheckKind.IS_NULL)),
                            ordering(),
                            SelectCaseModel.ResultShape.ROW_SET),
                    renderNotExistsSource(),
                    renderNullCheckSource(true));
            case CORRELATED_SCALAR_SELF_EMAIL_NONNULL -> build(
                    family,
                    SelectCaseModel.selectCase(
                            SubqueryEquivalenceProfile.SUBQUERY_FORMS.id(),
                            SelectCaseModel.SourceTable.ACCOUNTS_FIXTURE,
                            projections(),
                            SelectCaseModel.and(
                                    SelectCaseModel.compare(
                                            SelectCaseModel.column("active", SelectCaseModel.ValueType.BOOLEAN),
                                            SelectCaseModel.ComparisonOperator.EQ,
                                            SelectCaseModel.boolLiteral(true)),
                                    SelectCaseModel.nullCheck(
                                            SelectCaseModel.column("email", SelectCaseModel.ValueType.TEXT),
                                            SelectCaseModel.NullCheckKind.IS_NOT_NULL)),
                            ordering(),
                            SelectCaseModel.ResultShape.ROW_SET),
                    renderScalarSelfLookupSource(),
                    renderEmailNullCheckSource());
            case CORRELATED_EXISTS_SELF_ID_EMAIL_NONNULL_JOIN -> build(
                    family,
                    SelectCaseModel.selectCase(
                            SubqueryEquivalenceProfile.SUBQUERY_FORMS.id(),
                            SelectCaseModel.SourceTable.ACCOUNTS_FIXTURE,
                            projections(),
                            SelectCaseModel.and(
                                    SelectCaseModel.compare(
                                            SelectCaseModel.column("active", SelectCaseModel.ValueType.BOOLEAN),
                                            SelectCaseModel.ComparisonOperator.EQ,
                                            SelectCaseModel.boolLiteral(true)),
                                    SelectCaseModel.nullCheck(
                                            SelectCaseModel.column("email", SelectCaseModel.ValueType.TEXT),
                                            SelectCaseModel.NullCheckKind.IS_NOT_NULL)),
                            ordering(),
                            SelectCaseModel.ResultShape.ROW_SET),
                    renderExistsSelfIdEmailNonnullSource(),
                    renderInnerJoinSelfIdEmailNonnullSource());
        };
    }

    private static SubqueryEquivalenceCaseModel.SubqueryEquivalenceCase build(
            SubqueryEquivalenceCaseModel.Family family,
            SelectCaseModel.SelectCase referenceCase,
            String subqueryJavaSource,
            String rewrittenJavaSource
    ) {
        return SubqueryEquivalenceCaseModel.of(
                SubqueryEquivalenceProfile.SUBQUERY_FORMS.id(),
                family,
                referenceCase,
                subqueryJavaSource,
                rewrittenJavaSource);
    }

    private static List<SelectCaseModel.Projection> projections() {
        return List.of(
                SelectCaseModel.projection("id", SelectCaseModel.column("id", SelectCaseModel.ValueType.INTEGER)),
                SelectCaseModel.projection("email", SelectCaseModel.column("email", SelectCaseModel.ValueType.TEXT)));
    }

    private static List<SelectCaseModel.Ordering> ordering() {
        return List.of(SelectCaseModel.orderBy("id", SelectCaseModel.ValueType.INTEGER, SelectCaseModel.SortDirection.ASC));
    }

    private static String renderExistsSource() {
        return renderCteWrappedQuery("""
                .where(DSL.exists(
                        select(ACCOUNTS.ID)
                                .from(ACCOUNTS)
                                .where(ACCOUNTS.PLAN_CODE.eqColumn(activePlanCode))
                                .limit(1)))
                """);
    }

    private static String renderNotExistsSource() {
        return renderCteWrappedQuery("""
                .where(DSL.notExists(
                        select(ACCOUNTS.ID)
                                .from(ACCOUNTS)
                                .where(ACCOUNTS.PLAN_CODE.eqColumn(activePlanCode))
                                .limit(1)))
                """);
    }

    private static String renderNullCheckSource(boolean isNull) {
        return renderCteWrappedQuery("""
                .where(%s)
                """.formatted(isNull ? "activePlanCode.isNull()" : "activePlanCode.isNotNull()"));
    }

    private static String renderScalarSelfLookupSource() {
        return renderCteWrappedQuery("""
                .where(activeEmail.eqColumn(
                        DSL.scalar(
                                select(ACCOUNTS.EMAIL)
                                        .from(ACCOUNTS)
                                        .where(ACCOUNTS.ID.eqColumn(activeId))
                                        .limit(1))))
                """);
    }

    private static String renderEmailNullCheckSource() {
        return renderCteWrappedQuery("""
                .where(activeEmail.isNotNull())
                """);
    }

    private static String renderExistsSelfIdEmailNonnullSource() {
        return renderCteWrappedQuery("""
                .where(DSL.exists(
                        select(ACCOUNTS.ID)
                                .from(ACCOUNTS)
                                .where(ACCOUNTS.ID.eqColumn(activeId)
                                        .and(ACCOUNTS.EMAIL.isNotNull()))
                                .limit(1)))
                """);
    }

    private static String renderInnerJoinSelfIdEmailNonnullSource() {
        return """
                import titan.dsl.*;
                import static titan.dsl.DSL.*;

                class SubqueryEquivalenceGenerated {
                    static final AccountsTable ACCOUNTS = new AccountsTable();

                    @StoredProcedure
                    static void run() {
                        CommonTableExpression<Object> active_accounts = DSL.name("active_accounts")
                                .as(select(ACCOUNTS.ID, ACCOUNTS.EMAIL, ACCOUNTS.PLAN_CODE)
                                        .from(ACCOUNTS)
                                        .where(ACCOUNTS.ACTIVE.eq(true)));
                        Column<Integer> activeId = active_accounts.field("id", SQLType.INTEGER, Nullability.NOT_NULL);
                        Column<String> activeEmail = active_accounts.field("email", SQLType.TEXT, Nullability.NULLABLE);
                        Column<String> activePlanCode = active_accounts.field("plan_code", SQLType.TEXT, Nullability.NULLABLE);

                        DSL.with(active_accounts)
                                .select(activeId, activeEmail)
                                .from(active_accounts)
                                .join(ACCOUNTS).on(activeId.eqColumn(ACCOUNTS.ID))
                                .where(ACCOUNTS.EMAIL.isNotNull())
                                .orderBy(activeId.asc())
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
                """;
    }

    private static String renderCteWrappedQuery(String whereClause) {
        return """
                import titan.dsl.*;
                import static titan.dsl.DSL.*;

                class SubqueryEquivalenceGenerated {
                    static final AccountsTable ACCOUNTS = new AccountsTable();

                    @StoredProcedure
                    static void run() {
                        CommonTableExpression<Object> active_accounts = DSL.name("active_accounts")
                                .as(select(ACCOUNTS.ID, ACCOUNTS.EMAIL, ACCOUNTS.PLAN_CODE)
                                        .from(ACCOUNTS)
                                        .where(ACCOUNTS.ACTIVE.eq(true)));
                        Column<Integer> activeId = active_accounts.field("id", SQLType.INTEGER, Nullability.NOT_NULL);
                        Column<String> activeEmail = active_accounts.field("email", SQLType.TEXT, Nullability.NULLABLE);
                        Column<String> activePlanCode = active_accounts.field("plan_code", SQLType.TEXT, Nullability.NULLABLE);

                        DSL.with(active_accounts)
                                .select(activeId, activeEmail)
                                .from(active_accounts)
                                %s
                                .orderBy(activeId.asc())
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
                """.formatted(whereClause.strip());
    }
}
