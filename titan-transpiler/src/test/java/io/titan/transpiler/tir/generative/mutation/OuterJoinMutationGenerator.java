package io.titan.transpiler.tir.generative.mutation;

import io.titan.transpiler.tir.generative.shared.JoinProfile;
import io.titan.transpiler.tir.generative.shared.JoinCaseModel;
import java.util.List;

final class OuterJoinMutationGenerator {

    OuterJoinMutationCaseModel.OuterJoinMutationCase generate(long seed) {
        OuterJoinMutationCaseModel.Family family = switch ((int) Math.floorMod(seed, 2)) {
            case 0 -> OuterJoinMutationCaseModel.Family.LEFT_JOIN_NULL_EXTENSION_EQCOLUMN_TO_PAIR;
            default -> OuterJoinMutationCaseModel.Family.LEFT_JOIN_DUPLICATE_NULL_EXTENSION_EQCOLUMN_TO_PAIR;
        };
        return switch (family) {
            case LEFT_JOIN_NULL_EXTENSION_EQCOLUMN_TO_PAIR -> build(
                    family,
                    JoinCaseModel.of(JoinProfile.INNER_JOIN_BASIC.id(), JoinCaseModel.Family.LEFT_JOIN_ACTIVE_NULL_EXTENSION));
            case LEFT_JOIN_DUPLICATE_NULL_EXTENSION_EQCOLUMN_TO_PAIR -> build(
                    family,
                    JoinCaseModel.of(JoinProfile.INNER_JOIN_BASIC.id(), JoinCaseModel.Family.LEFT_JOIN_DUPLICATE_NULL_EXTENSION));
        };
    }

    private static OuterJoinMutationCaseModel.OuterJoinMutationCase build(
            OuterJoinMutationCaseModel.Family family,
            JoinCaseModel.JoinCase baseCase
    ) {
        return OuterJoinMutationCaseModel.of(
                OuterJoinMutationProfile.OUTER_JOIN_FORMS.id(),
                family,
                baseCase,
                MutationCaseModel.MutatorId.ADD_SAFE_CONJUNCT,
                MutationCaseModel.parameterSet(
                        MutationCaseModel.parameter("joinRewrite", "left-eqcolumn-to-pair"),
                        MutationCaseModel.parameter("nullExtension", family.id())),
                renderExplicitSource(),
                renderHelperSource(),
                MutationCaseModel.MutationClassification.STABLE_PASS,
                List.of());
    }

    private static String renderExplicitSource() {
        return renderSource(".leftJoin(PLANS).on(ACCOUNTS.PLAN_CODE.eqColumn(PLANS.CODE))");
    }

    private static String renderHelperSource() {
        return renderSource(".leftJoin(PLANS).on(ACCOUNTS.PLAN_CODE, PLANS.CODE)");
    }

    private static String renderSource(String joinClause) {
        return """
                import titan.dsl.*;
                import static titan.dsl.DSL.*;

                class OuterJoinMutationGenerated {
                    static final AccountsTable ACCOUNTS = new AccountsTable();
                    static final PlansTable PLANS = new PlansTable();

                    @StoredProcedure
                    static void run() {
                        select(ACCOUNTS.ID, PLANS.NAME)
                                .from(ACCOUNTS)
                                %s
                                .where(ACCOUNTS.ACTIVE.eq(true))
                                .orderBy(ACCOUNTS.ID.asc(), PLANS.NAME.asc())
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
                """.formatted(joinClause);
    }
}
