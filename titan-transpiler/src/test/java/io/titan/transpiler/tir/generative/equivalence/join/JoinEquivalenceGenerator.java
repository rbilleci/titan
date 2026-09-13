package io.titan.transpiler.tir.generative.equivalence.join;

import io.titan.transpiler.tir.generative.shared.JoinProfile;
import io.titan.transpiler.tir.generative.shared.JoinCaseModel;
final class JoinEquivalenceGenerator {

    JoinEquivalenceCaseModel.JoinEquivalenceCase generate(long seed) {
        JoinEquivalenceCaseModel.Family family = switch ((int) Math.floorMod(seed, 3)) {
            case 0 -> JoinEquivalenceCaseModel.Family.INNER_JOIN_ACTIVE_ON_EQCOLUMN_VS_PAIR;
            case 1 -> JoinEquivalenceCaseModel.Family.INNER_JOIN_PAID_ON_EQCOLUMN_VS_PAIR;
            default -> JoinEquivalenceCaseModel.Family.TWO_HOP_FILTERED_SECOND_HOP_EQCOLUMN_VS_FILTERED_PAIR;
        };
        return switch (family) {
            case INNER_JOIN_ACTIVE_ON_EQCOLUMN_VS_PAIR -> build(
                    family,
                    JoinCaseModel.of(JoinProfile.INNER_JOIN_BASIC.id(), JoinCaseModel.Family.INNER_JOIN_ACTIVE_ACCOUNTS),
                    renderExplicitSource("ACCOUNTS.ACTIVE.eq(true)"),
                    renderHelperSource("ACCOUNTS.ACTIVE.eq(true)"));
            case INNER_JOIN_PAID_ON_EQCOLUMN_VS_PAIR -> build(
                    family,
                    JoinCaseModel.of(JoinProfile.INNER_JOIN_BASIC.id(), JoinCaseModel.Family.INNER_JOIN_PAID_PLANS),
                    renderExplicitSource("PLANS.PAID.eq(true)"),
                    renderHelperSource("PLANS.PAID.eq(true)"));
            case TWO_HOP_FILTERED_SECOND_HOP_EQCOLUMN_VS_FILTERED_PAIR -> build(
                    family,
                    JoinCaseModel.of(JoinProfile.INNER_JOIN_BASIC.id(), JoinCaseModel.Family.TWO_JOIN_SECOND_HOP_FILTER),
                    renderFilteredSecondHopExplicitSource(),
                    renderFilteredSecondHopHelperSource());
        };
    }

    private static JoinEquivalenceCaseModel.JoinEquivalenceCase build(
            JoinEquivalenceCaseModel.Family family,
            JoinCaseModel.JoinCase referenceCase,
            String explicitJavaSource,
            String helperJavaSource
    ) {
        return JoinEquivalenceCaseModel.of(
                JoinEquivalenceProfile.JOIN_FORMS.id(),
                family,
                referenceCase,
                explicitJavaSource,
                helperJavaSource);
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

                class JoinEquivalenceGenerated {
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

    private static String renderFilteredSecondHopExplicitSource() {
        return renderTwoHopSource(
                ".join(PLAN_FAMILIES).on(PLANS.FAMILY_CODE.eqColumn(PLAN_FAMILIES.CODE).and(PLAN_FAMILIES.LABEL.eq(\"Growth\")))");
    }

    private static String renderFilteredSecondHopHelperSource() {
        return renderTwoHopSource(
                ".join(PLAN_FAMILIES).on(PLANS.FAMILY_CODE, PLAN_FAMILIES.CODE, PLAN_FAMILIES.LABEL.eq(\"Growth\"))");
    }

    private static String renderTwoHopSource(String secondHopJoinClause) {
        return """
                import titan.dsl.*;
                import static titan.dsl.DSL.*;

                class JoinEquivalenceGenerated {
                    static final AccountsTable ACCOUNTS = new AccountsTable();
                    static final PlansTable PLANS = new PlansTable();
                    static final PlanFamiliesTable PLAN_FAMILIES = new PlanFamiliesTable();

                    @StoredProcedure
                    static void run() {
                        select(ACCOUNTS.ID, PLAN_FAMILIES.LABEL)
                                .from(ACCOUNTS)
                                .join(PLANS).on(ACCOUNTS.PLAN_CODE.eqColumn(PLANS.CODE))
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
