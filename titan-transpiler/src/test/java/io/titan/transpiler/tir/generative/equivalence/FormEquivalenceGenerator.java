package io.titan.transpiler.tir.generative.equivalence;

import io.titan.transpiler.tir.generative.shared.SelectCaseModel;
import java.util.List;

final class FormEquivalenceGenerator {

    FormEquivalenceCaseModel.FormEquivalenceCase generate(long seed) {
        FormEquivalenceCaseModel.Family family = switch ((int) Math.floorMod(seed, 3)) {
            case 0 -> FormEquivalenceCaseModel.Family.SELECT_FROM_ACTIVE_ROWS;
            case 1 -> FormEquivalenceCaseModel.Family.SELECT_FROM_NONNULL_EMAIL;
            default -> FormEquivalenceCaseModel.Family.SELECT_FROM_NULL_EMAIL_ROWS;
        };
        return switch (family) {
            case SELECT_FROM_ACTIVE_ROWS -> build(
                    family,
                    SelectCaseModel.selectCase(
                            FormEquivalenceProfile.HELPER_EXPLICIT.id(),
                            SelectCaseModel.SourceTable.ACCOUNTS_FIXTURE,
                            projections(),
                            SelectCaseModel.compare(
                                    SelectCaseModel.column("active", SelectCaseModel.ValueType.BOOLEAN),
                                    SelectCaseModel.ComparisonOperator.EQ,
                                    SelectCaseModel.boolLiteral(true)),
                            List.of(SelectCaseModel.orderBy("id", SelectCaseModel.ValueType.INTEGER, SelectCaseModel.SortDirection.ASC)),
                            SelectCaseModel.ResultShape.ROW_SET),
                    "ACCOUNTS.ACTIVE.eq(true)");
            case SELECT_FROM_NONNULL_EMAIL -> build(
                    family,
                    SelectCaseModel.selectCase(
                            FormEquivalenceProfile.HELPER_EXPLICIT.id(),
                            SelectCaseModel.SourceTable.ACCOUNTS_FIXTURE,
                            projections(),
                            SelectCaseModel.nullCheck(
                                    SelectCaseModel.column("email", SelectCaseModel.ValueType.TEXT),
                                    SelectCaseModel.NullCheckKind.IS_NOT_NULL),
                            List.of(SelectCaseModel.orderBy("id", SelectCaseModel.ValueType.INTEGER, SelectCaseModel.SortDirection.ASC)),
                            SelectCaseModel.ResultShape.ROW_SET),
                    "ACCOUNTS.EMAIL.isNotNull()");
            case SELECT_FROM_NULL_EMAIL_ROWS -> build(
                    family,
                    SelectCaseModel.selectCase(
                            FormEquivalenceProfile.HELPER_EXPLICIT.id(),
                            SelectCaseModel.SourceTable.ACCOUNTS_FIXTURE,
                            projections(),
                            SelectCaseModel.nullCheck(
                                    SelectCaseModel.column("email", SelectCaseModel.ValueType.TEXT),
                                    SelectCaseModel.NullCheckKind.IS_NULL),
                            List.of(SelectCaseModel.orderBy("id", SelectCaseModel.ValueType.INTEGER, SelectCaseModel.SortDirection.ASC)),
                            SelectCaseModel.ResultShape.ROW_SET),
                    "ACCOUNTS.EMAIL.isNull()");
        };
    }

    private static FormEquivalenceCaseModel.FormEquivalenceCase build(
            FormEquivalenceCaseModel.Family family,
            SelectCaseModel.SelectCase referenceCase,
            String whereCondition
    ) {
        String helperJavaSource = renderHelperSource(whereCondition);
        String explicitJavaSource = renderExplicitSource(whereCondition);
        return FormEquivalenceCaseModel.of(
                FormEquivalenceProfile.HELPER_EXPLICIT.id(),
                family,
                referenceCase,
                helperJavaSource,
                explicitJavaSource);
    }

    private static List<SelectCaseModel.Projection> projections() {
        return List.of(
                SelectCaseModel.projection("id", SelectCaseModel.column("id", SelectCaseModel.ValueType.INTEGER)),
                SelectCaseModel.projection("email", SelectCaseModel.column("email", SelectCaseModel.ValueType.TEXT)),
                SelectCaseModel.projection("active", SelectCaseModel.column("active", SelectCaseModel.ValueType.BOOLEAN)),
                SelectCaseModel.projection("plan_code", SelectCaseModel.column("plan_code", SelectCaseModel.ValueType.TEXT)),
                SelectCaseModel.projection("login_count", SelectCaseModel.column("login_count", SelectCaseModel.ValueType.INTEGER)));
    }

    private static String renderHelperSource(String whereCondition) {
        return renderSource("selectFrom(ACCOUNTS)", whereCondition);
    }

    private static String renderExplicitSource(String whereCondition) {
        return renderSource("select(ACCOUNTS.ID, ACCOUNTS.EMAIL, ACCOUNTS.ACTIVE, ACCOUNTS.PLAN_CODE, ACCOUNTS.LOGIN_COUNT).from(ACCOUNTS)", whereCondition);
    }

    private static String renderSource(String selectStart, String whereCondition) {
        return """
                import titan.dsl.*;
                import static titan.dsl.DSL.*;

                class FormEquivalenceGenerated {
                    static final AccountsTable ACCOUNTS = new AccountsTable();

                    @StoredProcedure
                    static void run() {
                        %s
                                .where(%s)
                                .orderBy(ACCOUNTS.ID.asc())
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
                """.formatted(selectStart, whereCondition);
    }
}
