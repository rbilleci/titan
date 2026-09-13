package io.titan.transpiler.tir.generative.differential;

final class AggregationDifferentialGenerator {

    AggregationDifferentialCaseModel.AggregationCase generate(long seed) {
        Family family = Family.values()[(int) Math.floorMod(seed, Family.values().length)];
        return switch (family) {
            case COUNT_BY_PLAN -> AggregationDifferentialCaseModel.aggregationCase(
                    AggregationDifferentialProfile.AGGREGATION_BASIC.id(),
                    AggregationDifferentialCaseModel.GroupKey.PLAN_CODE,
                    AggregationDifferentialCaseModel.AggregateKind.COUNT_ALL,
                    AggregationDifferentialCaseModel.HavingKind.NONE,
                    "plan_code",
                    "row_count");
            case SUM_LOGIN_BY_ACTIVE -> AggregationDifferentialCaseModel.aggregationCase(
                    AggregationDifferentialProfile.AGGREGATION_BASIC.id(),
                    AggregationDifferentialCaseModel.GroupKey.ACTIVE,
                    AggregationDifferentialCaseModel.AggregateKind.SUM_LOGIN_COUNT,
                    AggregationDifferentialCaseModel.HavingKind.NONE,
                    "active",
                    "login_sum");
            case COUNT_BY_ACTIVE -> AggregationDifferentialCaseModel.aggregationCase(
                    AggregationDifferentialProfile.AGGREGATION_BASIC.id(),
                    AggregationDifferentialCaseModel.GroupKey.ACTIVE,
                    AggregationDifferentialCaseModel.AggregateKind.COUNT_ALL,
                    AggregationDifferentialCaseModel.HavingKind.NONE,
                    "active",
                    "row_count");
            case COUNT_BY_PLAN_HAVING -> AggregationDifferentialCaseModel.aggregationCase(
                    AggregationDifferentialProfile.AGGREGATION_BASIC.id(),
                    AggregationDifferentialCaseModel.GroupKey.PLAN_CODE,
                    AggregationDifferentialCaseModel.AggregateKind.COUNT_ALL,
                    AggregationDifferentialCaseModel.HavingKind.COUNT_GT_ONE,
                    "plan_code",
                    "row_count");
            case SUM_LOGIN_BY_ACTIVE_HAVING -> AggregationDifferentialCaseModel.aggregationCase(
                    AggregationDifferentialProfile.AGGREGATION_BASIC.id(),
                    AggregationDifferentialCaseModel.GroupKey.ACTIVE,
                    AggregationDifferentialCaseModel.AggregateKind.SUM_LOGIN_COUNT,
                    AggregationDifferentialCaseModel.HavingKind.SUM_GT_TEN,
                    "active",
                    "login_sum");
            case MIN_LOGIN_BY_PLAN -> AggregationDifferentialCaseModel.aggregationCase(
                    AggregationDifferentialProfile.AGGREGATION_BASIC.id(),
                    AggregationDifferentialCaseModel.GroupKey.PLAN_CODE,
                    AggregationDifferentialCaseModel.AggregateKind.MIN_LOGIN_COUNT,
                    AggregationDifferentialCaseModel.HavingKind.NONE,
                    "plan_code",
                    "login_min");
            case MAX_LOGIN_BY_ACTIVE -> AggregationDifferentialCaseModel.aggregationCase(
                    AggregationDifferentialProfile.AGGREGATION_BASIC.id(),
                    AggregationDifferentialCaseModel.GroupKey.ACTIVE,
                    AggregationDifferentialCaseModel.AggregateKind.MAX_LOGIN_COUNT,
                    AggregationDifferentialCaseModel.HavingKind.NONE,
                    "active",
                    "login_max");
        };
    }

    private enum Family {
        COUNT_BY_PLAN,
        SUM_LOGIN_BY_ACTIVE,
        COUNT_BY_ACTIVE,
        COUNT_BY_PLAN_HAVING,
        SUM_LOGIN_BY_ACTIVE_HAVING,
        MIN_LOGIN_BY_PLAN,
        MAX_LOGIN_BY_ACTIVE
    }
}
