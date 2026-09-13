package io.titan.transpiler.tir.generative.differential;

import io.titan.transpiler.tir.generative.shared.FixtureCatalog;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class AggregationDifferentialReferenceEvaluatorTest {

    private final AggregationDifferentialReferenceEvaluator evaluator = new AggregationDifferentialReferenceEvaluator();

    @Test
    void evaluatesCountGroupedByPlanCode() {
        var aggregationCase = AggregationDifferentialCaseModel.aggregationCase(
                AggregationDifferentialProfile.AGGREGATION_BASIC.id(),
                AggregationDifferentialCaseModel.GroupKey.PLAN_CODE,
                AggregationDifferentialCaseModel.AggregateKind.COUNT_ALL,
                AggregationDifferentialCaseModel.HavingKind.NONE,
                "plan_code",
                "row_count");

        var result = evaluator.evaluate(aggregationCase, FixtureCatalog.accountsFixture());

        assertEquals(
                "[{\"plan_code\": \"enterprise\", \"row_count\": 1}, {\"plan_code\": \"free\", \"row_count\": 2}, {\"plan_code\": \"pro\", \"row_count\": 2}, {\"plan_code\": null, \"row_count\": 1}]",
                result.toStableJson());
    }

    @Test
    void evaluatesSumGroupedByActiveWithNullPropagation() {
        var aggregationCase = AggregationDifferentialCaseModel.aggregationCase(
                AggregationDifferentialProfile.AGGREGATION_BASIC.id(),
                AggregationDifferentialCaseModel.GroupKey.ACTIVE,
                AggregationDifferentialCaseModel.AggregateKind.SUM_LOGIN_COUNT,
                AggregationDifferentialCaseModel.HavingKind.NONE,
                "active",
                "login_sum");

        var result = evaluator.evaluate(aggregationCase, FixtureCatalog.accountsFixture());

        assertEquals(
                "[{\"active\": false, \"login_sum\": 1}, {\"active\": true, \"login_sum\": 31}, {\"active\": null, \"login_sum\": null}]",
                result.toStableJson());
    }

    @Test
    void appliesHavingFilterAfterGrouping() {
        var aggregationCase = AggregationDifferentialCaseModel.aggregationCase(
                AggregationDifferentialProfile.AGGREGATION_BASIC.id(),
                AggregationDifferentialCaseModel.GroupKey.PLAN_CODE,
                AggregationDifferentialCaseModel.AggregateKind.COUNT_ALL,
                AggregationDifferentialCaseModel.HavingKind.COUNT_GT_ONE,
                "plan_code",
                "row_count");

        var result = evaluator.evaluate(aggregationCase, FixtureCatalog.accountsFixture());

        assertEquals(
                "[{\"plan_code\": \"free\", \"row_count\": 2}, {\"plan_code\": \"pro\", \"row_count\": 2}]",
                result.toStableJson());
    }

    @Test
    void evaluatesMinAndMaxGroupedAggregates() {
        var minCase = AggregationDifferentialCaseModel.aggregationCase(
                AggregationDifferentialProfile.AGGREGATION_BASIC.id(),
                AggregationDifferentialCaseModel.GroupKey.PLAN_CODE,
                AggregationDifferentialCaseModel.AggregateKind.MIN_LOGIN_COUNT,
                AggregationDifferentialCaseModel.HavingKind.NONE,
                "plan_code",
                "login_min");
        var maxCase = AggregationDifferentialCaseModel.aggregationCase(
                AggregationDifferentialProfile.AGGREGATION_BASIC.id(),
                AggregationDifferentialCaseModel.GroupKey.ACTIVE,
                AggregationDifferentialCaseModel.AggregateKind.MAX_LOGIN_COUNT,
                AggregationDifferentialCaseModel.HavingKind.NONE,
                "active",
                "login_max");

        var minResult = evaluator.evaluate(minCase, FixtureCatalog.accountsFixture());
        var maxResult = evaluator.evaluate(maxCase, FixtureCatalog.accountsFixture());

        assertEquals(
                "[{\"login_min\": 12, \"plan_code\": \"enterprise\"}, {\"login_min\": 0, \"plan_code\": \"free\"}, {\"login_min\": 1, \"plan_code\": \"pro\"}, {\"login_min\": 7, \"plan_code\": null}]",
                minResult.toStableJson());
        assertEquals(
                "[{\"active\": false, \"login_max\": 1}, {\"active\": true, \"login_max\": 12}, {\"active\": null, \"login_max\": null}]",
                maxResult.toStableJson());
    }
}
