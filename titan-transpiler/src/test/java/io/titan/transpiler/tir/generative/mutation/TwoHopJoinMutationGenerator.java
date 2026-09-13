package io.titan.transpiler.tir.generative.mutation;

import io.titan.transpiler.tir.generative.shared.JoinProfile;
import io.titan.transpiler.tir.generative.shared.JoinCaseModel;
import java.util.List;

final class TwoHopJoinMutationGenerator {

    TwoHopJoinMutationCaseModel.TwoHopJoinMutationCase generate(long seed) {
        TwoHopJoinMutationCaseModel.Family family = switch ((int) Math.floorMod(seed, 4)) {
            case 0 -> TwoHopJoinMutationCaseModel.Family.TWO_HOP_ACTIVE_SECOND_HOP_EQCOLUMN_TO_PAIR;
            case 1 -> TwoHopJoinMutationCaseModel.Family.LEFT_TWO_HOP_NULL_EXTENSION_SECOND_HOP_EQCOLUMN_TO_PAIR;
            case 2 -> TwoHopJoinMutationCaseModel.Family.LEFT_TWO_HOP_FILTERED_SECOND_HOP_EQCOLUMN_TO_FILTERED_PAIR;
            default -> TwoHopJoinMutationCaseModel.Family.TWO_HOP_FILTERED_SECOND_HOP_EQCOLUMN_TO_FILTERED_PAIR;
        };
        return switch (family) {
            case TWO_HOP_ACTIVE_SECOND_HOP_EQCOLUMN_TO_PAIR -> build(
                    family,
                    JoinCaseModel.of(JoinProfile.INNER_JOIN_BASIC.id(), JoinCaseModel.Family.TWO_JOIN_ACTIVE_PLAN_FAMILY),
                    false,
                    false);
            case LEFT_TWO_HOP_NULL_EXTENSION_SECOND_HOP_EQCOLUMN_TO_PAIR -> build(
                    family,
                    JoinCaseModel.of(JoinProfile.INNER_JOIN_BASIC.id(), JoinCaseModel.Family.LEFT_TWO_JOIN_NULL_EXTENSION),
                    true,
                    false);
            case LEFT_TWO_HOP_FILTERED_SECOND_HOP_EQCOLUMN_TO_FILTERED_PAIR -> build(
                    family,
                    JoinCaseModel.of(JoinProfile.INNER_JOIN_BASIC.id(), JoinCaseModel.Family.LEFT_TWO_JOIN_SECOND_HOP_FILTER),
                    true,
                    true);
            case TWO_HOP_FILTERED_SECOND_HOP_EQCOLUMN_TO_FILTERED_PAIR -> build(
                    family,
                    JoinCaseModel.of(JoinProfile.INNER_JOIN_BASIC.id(), JoinCaseModel.Family.TWO_JOIN_SECOND_HOP_FILTER),
                    false,
                    true);
        };
    }

    private static TwoHopJoinMutationCaseModel.TwoHopJoinMutationCase build(
            TwoHopJoinMutationCaseModel.Family family,
            JoinCaseModel.JoinCase baseCase,
            boolean leftSecondHop,
            boolean filteredSecondHop
    ) {
        return TwoHopJoinMutationCaseModel.of(
                TwoHopJoinMutationProfile.TWO_HOP_JOIN_FORMS.id(),
                family,
                baseCase,
                MutationCaseModel.MutatorId.ADD_SAFE_CONJUNCT,
                MutationCaseModel.parameterSet(
                        MutationCaseModel.parameter("secondHopRewrite", filteredSecondHop
                                ? "left-eqcolumn-to-filtered-pair"
                                : (leftSecondHop ? "left-eqcolumn-to-pair" : "eqcolumn-to-pair")),
                        MutationCaseModel.parameter("family", family.id())),
                renderBaseSource(leftSecondHop, filteredSecondHop),
                renderMutatedSource(leftSecondHop, filteredSecondHop),
                MutationCaseModel.MutationClassification.STABLE_PASS,
                List.of());
    }

    private static String renderBaseSource(boolean leftSecondHop, boolean filteredSecondHop) {
        return renderSource(leftSecondHop, false, filteredSecondHop);
    }

    private static String renderMutatedSource(boolean leftSecondHop, boolean filteredSecondHop) {
        return renderSource(leftSecondHop, true, filteredSecondHop);
    }

    private static String renderSource(boolean leftSecondHop, boolean mutateSecondHop, boolean filteredSecondHop) {
        String secondHop = filteredSecondHop
                ? (leftSecondHop
                    ? (mutateSecondHop
                        ? ".leftJoin(PLAN_FAMILIES).on(PLANS.FAMILY_CODE, PLAN_FAMILIES.CODE, PLAN_FAMILIES.LABEL.eq(\"Growth\"))"
                        : ".leftJoin(PLAN_FAMILIES).on(PLANS.FAMILY_CODE.eqColumn(PLAN_FAMILIES.CODE).and(PLAN_FAMILIES.LABEL.eq(\"Growth\")))")
                    : (mutateSecondHop
                        ? ".join(PLAN_FAMILIES).on(PLANS.FAMILY_CODE, PLAN_FAMILIES.CODE, PLAN_FAMILIES.LABEL.eq(\"Growth\"))"
                        : ".join(PLAN_FAMILIES).on(PLANS.FAMILY_CODE.eqColumn(PLAN_FAMILIES.CODE).and(PLAN_FAMILIES.LABEL.eq(\"Growth\")))"))
                : leftSecondHop
                    ? (mutateSecondHop
                        ? ".leftJoin(PLAN_FAMILIES).on(PLANS.FAMILY_CODE, PLAN_FAMILIES.CODE)"
                        : ".leftJoin(PLAN_FAMILIES).on(PLANS.FAMILY_CODE.eqColumn(PLAN_FAMILIES.CODE))")
                    : (mutateSecondHop
                        ? ".join(PLAN_FAMILIES).on(PLANS.FAMILY_CODE, PLAN_FAMILIES.CODE)"
                        : ".join(PLAN_FAMILIES).on(PLANS.FAMILY_CODE.eqColumn(PLAN_FAMILIES.CODE))");
        String firstHop = leftSecondHop
                ? ".leftJoin(PLANS).on(ACCOUNTS.PLAN_CODE.eqColumn(PLANS.CODE))"
                : ".join(PLANS).on(ACCOUNTS.PLAN_CODE.eqColumn(PLANS.CODE))";
        return """
                import titan.dsl.*;
                import static titan.dsl.DSL.*;

                class TwoHopJoinMutationGenerated {
                    static final AccountsTable ACCOUNTS = new AccountsTable();
                    static final PlansTable PLANS = new PlansTable();
                    static final PlanFamiliesTable PLAN_FAMILIES = new PlanFamiliesTable();

                    @StoredProcedure
                    static void run() {
                        select(ACCOUNTS.ID, PLAN_FAMILIES.LABEL)
                                .from(ACCOUNTS)
                                %s
                                %s
                                .where(ACCOUNTS.ACTIVE.eq(true))
                                .orderBy(ACCOUNTS.ID.asc())
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
                        public final Column<String> FAMILY_CODE = column("family_code", SQLType.TEXT, Nullability.NOT_NULL);

                        PlansTable() {
                            super("plans", "public");
                        }
                    }

                    static final class PlanFamiliesTable extends Table<Object> {
                        public final Column<String> CODE = column("code", SQLType.TEXT, Nullability.NOT_NULL);
                        public final Column<String> LABEL = column("label", SQLType.TEXT, Nullability.NOT_NULL);

                        PlanFamiliesTable() {
                            super("plan_families", "public");
                        }
                    }
                }
                """.formatted(firstHop, secondHop);
    }
}
