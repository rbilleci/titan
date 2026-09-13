package io.titan.transpiler.tir.generative.equivalence.outerjoin;

import io.titan.transpiler.tir.generative.shared.JoinProfile;
import io.titan.transpiler.tir.generative.shared.JoinCaseModel;
final class OuterJoinEquivalenceGenerator {

    OuterJoinEquivalenceCaseModel.OuterJoinEquivalenceCase generate(long seed) {
        OuterJoinEquivalenceCaseModel.Family family = switch ((int) Math.floorMod(seed, 3)) {
            case 0 -> OuterJoinEquivalenceCaseModel.Family.LEFT_JOIN_NULL_EXTENSION_EQCOLUMN_VS_PAIR;
            case 1 -> OuterJoinEquivalenceCaseModel.Family.LEFT_JOIN_DUPLICATE_NULL_EXTENSION_EQCOLUMN_VS_PAIR;
            default -> OuterJoinEquivalenceCaseModel.Family.LEFT_TWO_HOP_FILTERED_SECOND_HOP_EQCOLUMN_VS_FILTERED_PAIR;
        };
        return switch (family) {
            case LEFT_JOIN_NULL_EXTENSION_EQCOLUMN_VS_PAIR -> build(
                    family,
                    JoinCaseModel.of(JoinProfile.INNER_JOIN_BASIC.id(), JoinCaseModel.Family.LEFT_JOIN_ACTIVE_NULL_EXTENSION),
                    renderExplicitSource(),
                    renderHelperSource());
            case LEFT_JOIN_DUPLICATE_NULL_EXTENSION_EQCOLUMN_VS_PAIR -> build(
                    family,
                    JoinCaseModel.of(JoinProfile.INNER_JOIN_BASIC.id(), JoinCaseModel.Family.LEFT_JOIN_DUPLICATE_NULL_EXTENSION),
                    renderExplicitSource(),
                    renderHelperSource());
            case LEFT_TWO_HOP_FILTERED_SECOND_HOP_EQCOLUMN_VS_FILTERED_PAIR -> build(
                    family,
                    JoinCaseModel.of(JoinProfile.INNER_JOIN_BASIC.id(), JoinCaseModel.Family.LEFT_TWO_JOIN_SECOND_HOP_FILTER),
                    renderFilteredSecondHopExplicitSource(),
                    renderFilteredSecondHopHelperSource());
        };
    }

    private static OuterJoinEquivalenceCaseModel.OuterJoinEquivalenceCase build(
            OuterJoinEquivalenceCaseModel.Family family,
            JoinCaseModel.JoinCase referenceCase,
            String explicitJavaSource,
            String helperJavaSource
    ) {
        return OuterJoinEquivalenceCaseModel.of(
                OuterJoinEquivalenceProfile.OUTER_JOIN_FORMS.id(),
                family,
                referenceCase,
                explicitJavaSource,
                helperJavaSource);
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

                class OuterJoinEquivalenceGenerated {
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

    private static String renderFilteredSecondHopExplicitSource() {
        return renderTwoHopSource(
                ".leftJoin(PLAN_FAMILIES).on(PLANS.FAMILY_CODE.eqColumn(PLAN_FAMILIES.CODE).and(PLAN_FAMILIES.LABEL.eq(\"Growth\")))");
    }

    private static String renderFilteredSecondHopHelperSource() {
        return renderTwoHopSource(
                ".leftJoin(PLAN_FAMILIES).on(PLANS.FAMILY_CODE, PLAN_FAMILIES.CODE, PLAN_FAMILIES.LABEL.eq(\"Growth\"))");
    }

    private static String renderTwoHopSource(String secondHopJoinClause) {
        return """
                import titan.dsl.*;
                import static titan.dsl.DSL.*;

                class OuterJoinEquivalenceGenerated {
                    static final AccountsTable ACCOUNTS = new AccountsTable();
                    static final PlansTable PLANS = new PlansTable();
                    static final PlanFamiliesTable PLAN_FAMILIES = new PlanFamiliesTable();

                    @StoredProcedure
                    static void run() {
                        select(ACCOUNTS.ID, PLAN_FAMILIES.LABEL)
                                .from(ACCOUNTS)
                                .leftJoin(PLANS).on(ACCOUNTS.PLAN_CODE.eqColumn(PLANS.CODE))
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
                """.formatted(secondHopJoinClause);
    }
}
