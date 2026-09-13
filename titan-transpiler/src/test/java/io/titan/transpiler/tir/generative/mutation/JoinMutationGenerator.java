package io.titan.transpiler.tir.generative.mutation;

import io.titan.transpiler.tir.generative.shared.JoinProfile;
import io.titan.transpiler.tir.generative.shared.JoinCaseModel;
import java.util.List;

final class JoinMutationGenerator {

    JoinMutationCaseModel.JoinMutationCase generate(long seed) {
        JoinMutationCaseModel.Family family = switch ((int) Math.floorMod(seed, 2)) {
            case 0 -> JoinMutationCaseModel.Family.INNER_JOIN_ACTIVE_EQCOLUMN_TO_PAIR;
            default -> JoinMutationCaseModel.Family.INNER_JOIN_PAID_EQCOLUMN_TO_PAIR;
        };
        return switch (family) {
            case INNER_JOIN_ACTIVE_EQCOLUMN_TO_PAIR -> build(
                    family,
                    JoinCaseModel.of(JoinProfile.INNER_JOIN_BASIC.id(), JoinCaseModel.Family.INNER_JOIN_ACTIVE_ACCOUNTS),
                    "ACCOUNTS.ACTIVE.eq(true)");
            case INNER_JOIN_PAID_EQCOLUMN_TO_PAIR -> build(
                    family,
                    JoinCaseModel.of(JoinProfile.INNER_JOIN_BASIC.id(), JoinCaseModel.Family.INNER_JOIN_PAID_PLANS),
                    "PLANS.PAID.eq(true)");
        };
    }

    private static JoinMutationCaseModel.JoinMutationCase build(
            JoinMutationCaseModel.Family family,
            JoinCaseModel.JoinCase baseCase,
            String whereCondition
    ) {
        return JoinMutationCaseModel.of(
                JoinMutationProfile.JOIN_FORMS.id(),
                family,
                baseCase,
                MutationCaseModel.MutatorId.ADD_SAFE_CONJUNCT,
                MutationCaseModel.parameterSet(
                        MutationCaseModel.parameter("joinRewrite", "eqcolumn-to-pair"),
                        MutationCaseModel.parameter("where", whereCondition)),
                renderExplicitSource(whereCondition),
                renderHelperSource(whereCondition),
                MutationCaseModel.MutationClassification.STABLE_PASS,
                List.of());
    }

    private static String renderExplicitSource(String whereCondition) {
        return renderSource(".join(PLANS).on(ACCOUNTS.PLAN_CODE.eqColumn(PLANS.CODE))", whereCondition);
    }

    private static String renderHelperSource(String whereCondition) {
        return renderSource(".join(PLANS).on(ACCOUNTS.PLAN_CODE, PLANS.CODE)", whereCondition);
    }

    private static String renderSource(String joinClause, String whereCondition) {
        return """
                import titan.dsl.*;
                import static titan.dsl.DSL.*;

                class PhaseDJoinMutationGenerated {
                    static final AccountsTable ACCOUNTS = new AccountsTable();
                    static final PlansTable PLANS = new PlansTable();

                    @StoredProcedure
                    static void run() {
                        select(ACCOUNTS.ID, PLANS.NAME)
                                .from(ACCOUNTS)
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

                    static final class PlansTable extends Table<Object> {
                        public final Column<String> CODE = column("code", SQLType.TEXT, Nullability.NOT_NULL);
                        public final Column<String> NAME = column("name", SQLType.TEXT, Nullability.NOT_NULL);
                        public final Column<Boolean> PAID = column("paid", SQLType.BOOLEAN, Nullability.NOT_NULL);
                        public final Column<String> FAMILY_CODE = column("family_code", SQLType.TEXT, Nullability.NOT_NULL);

                        PlansTable() {
                            super("plans", "public");
                        }
                    }
                }
                """.formatted(joinClause, whereCondition);
    }
}
