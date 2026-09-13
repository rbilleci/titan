package io.titan.transpiler.tir.generative.conformance;

import java.util.List;

final class TranspilerJoinCompositionGenerator {

    GeneratedTranspilerCase generate(long seed) {
        JoinCompositionFamily family = JoinCompositionFamily.values()[(int) Math.floorMod(seed, JoinCompositionFamily.values().length)];
        String className = "GeneratedJoinCompositionCase" + Long.toUnsignedString(seed);

        String query = switch (family) {
            case JOIN_FILTER_ORDER -> "select(ACCOUNTS.ID, PLANS.NAME)"
                    + ".from(ACCOUNTS)"
                    + ".join(PLANS).on(ACCOUNTS.PLAN_ID.eqColumn(PLANS.ID))"
                    + ".where(ACCOUNTS.ACTIVE.eq(true))"
                    + ".orderBy(PLANS.NAME.asc())"
                    + ".fetch();";
            case LEFT_JOIN_AGGREGATE -> "select(PLANS.NAME, count())"
                    + ".from(ACCOUNTS)"
                    + ".leftJoin(PLANS).on(ACCOUNTS.PLAN_ID.eqColumn(PLANS.ID))"
                    + ".groupBy(PLANS.NAME)"
                    + ".having(count().gt(0L))"
                    + ".orderBy(count().desc())"
                    + ".fetch();";
            case JOIN_SCALAR_SUBQUERY -> "Column<Long> firstPlanId = scalar(select(PLANS.ID).from(PLANS).limit(1), SQLType.BIGINT);"
                    + "select(ACCOUNTS.ID, PLANS.NAME)"
                    + ".from(ACCOUNTS)"
                    + ".join(PLANS).on(ACCOUNTS.PLAN_ID.eqColumn(PLANS.ID))"
                    + ".where(PLANS.ID.eqColumn(firstPlanId))"
                    + ".fetch();";
            case RIGHT_JOIN_MULTI_GROUP -> "select(PLANS.NAME, ACCOUNTS.ACTIVE, count())"
                    + ".from(ACCOUNTS)"
                    + ".rightJoin(PLANS).on(ACCOUNTS.PLAN_ID.eqColumn(PLANS.ID))"
                    + ".groupBy(PLANS.NAME, ACCOUNTS.ACTIVE)"
                    + ".having(PLANS.NAME.isNotNull())"
                    + ".fetch();";
        };

        String source = """
                import titan.dsl.*;
                import static titan.dsl.DSL.*;

                class %s {
                    static final AccountsTable ACCOUNTS = new AccountsTable();
                    static final PlansTable PLANS = new PlansTable();

                    @StoredProcedure
                    static void run() {
                        %s
                    }

                    static final class AccountsTable extends Table<Object> {
                        final Column<Long> ID = column("id", SQLType.BIGINT, Nullability.NOT_NULL);
                        final Column<Boolean> ACTIVE = column("active", SQLType.BOOLEAN, Nullability.NOT_NULL);
                        final Column<Long> PLAN_ID = column("plan_id", SQLType.BIGINT, Nullability.NULLABLE);

                        AccountsTable() {
                            super("accounts", "public");
                        }
                    }

                    static final class PlansTable extends Table<Object> {
                        final Column<Long> ID = column("id", SQLType.BIGINT, Nullability.NOT_NULL);
                        final Column<String> NAME = column("name", SQLType.VARCHAR, Nullability.NOT_NULL);

                        PlansTable() {
                            super("plans", "public");
                        }
                    }
                }
                """.formatted(className, query);

        return new GeneratedTranspilerCase(
                seed,
                "transpiler-join-composition",
                family.id,
                className,
                source,
                "family=" + family.id + ", query=" + query,
                false,
                family.expectedFragments
        );
    }

    private enum JoinCompositionFamily {
        JOIN_FILTER_ORDER("join-filter-order", List.of("join", "plans", "order by")),
        LEFT_JOIN_AGGREGATE("left-join-aggregate", List.of("left join", "group by", "count(")),
        JOIN_SCALAR_SUBQUERY("join-scalar-subquery", List.of("join", "plans", "select")),
        RIGHT_JOIN_MULTI_GROUP("right-join-multi-group", List.of("right join", "group by", "count("));

        private final String id;
        private final List<String> expectedFragments;

        JoinCompositionFamily(String id, List<String> expectedFragments) {
            this.id = id;
            this.expectedFragments = expectedFragments;
        }
    }
}
