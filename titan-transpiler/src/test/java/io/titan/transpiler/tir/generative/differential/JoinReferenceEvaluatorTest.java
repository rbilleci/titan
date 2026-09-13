package io.titan.transpiler.tir.generative.differential;

import io.titan.transpiler.tir.generative.shared.JoinProfile;
import io.titan.transpiler.tir.generative.shared.JoinReferenceEvaluator;
import io.titan.transpiler.tir.generative.shared.JoinCaseModel;
import io.titan.transpiler.tir.generative.shared.FixtureCatalog;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class JoinReferenceEvaluatorTest {

    private final JoinReferenceEvaluator evaluator = new JoinReferenceEvaluator();

    @Test
    void evaluatesInnerJoinActiveAccountsFamily() {
        var result = evaluator.evaluate(
                JoinCaseModel.of(JoinProfile.INNER_JOIN_BASIC.id(), JoinCaseModel.Family.INNER_JOIN_ACTIVE_ACCOUNTS),
                FixtureCatalog.accountsFixture(),
                FixtureCatalog.plansFixture(),
                FixtureCatalog.planFamiliesFixture());

        assertEquals(
                "[{\"account_id\": 1, \"plan_name\": \"Free\"}, {\"account_id\": 5, \"plan_name\": \"Enterprise\"}]",
                result.toStableJson());
    }

    @Test
    void evaluatesInnerJoinPaidPlansFamily() {
        var result = evaluator.evaluate(
                JoinCaseModel.of(JoinProfile.INNER_JOIN_BASIC.id(), JoinCaseModel.Family.INNER_JOIN_PAID_PLANS),
                FixtureCatalog.accountsFixture(),
                FixtureCatalog.plansFixture(),
                FixtureCatalog.planFamiliesFixture());

        assertEquals(
                "[{\"account_id\": 2, \"plan_name\": \"Pro\"}, {\"account_id\": 4, \"plan_name\": \"Pro\"}, {\"account_id\": 5, \"plan_name\": \"Enterprise\"}]",
                result.toStableJson());
    }

    @Test
    void evaluatesInnerJoinDuplicatePlanMatchesFamily() {
        var result = evaluator.evaluate(
                JoinCaseModel.of(JoinProfile.INNER_JOIN_BASIC.id(), JoinCaseModel.Family.INNER_JOIN_DUPLICATE_PLAN_MATCHES),
                FixtureCatalog.accountsFixture(),
                FixtureCatalog.plansFixtureWithDuplicateFree(),
                FixtureCatalog.planFamiliesFixture());

        assertEquals(
                "[{\"account_id\": 1, \"plan_name\": \"Free Plus\"}, {\"account_id\": 1, \"plan_name\": \"Free\"}, {\"account_id\": 5, \"plan_name\": \"Enterprise\"}]",
                result.toStableJson());
    }

    @Test
    void evaluatesLeftJoinActiveNullExtensionFamily() {
        var result = evaluator.evaluate(
                JoinCaseModel.of(JoinProfile.INNER_JOIN_BASIC.id(), JoinCaseModel.Family.LEFT_JOIN_ACTIVE_NULL_EXTENSION),
                FixtureCatalog.accountsFixture(),
                FixtureCatalog.plansFixture(),
                FixtureCatalog.planFamiliesFixture());

        assertEquals(
                "[{\"account_id\": 1, \"plan_name\": \"Free\"}, {\"account_id\": 3, \"plan_name\": null}, {\"account_id\": 5, \"plan_name\": \"Enterprise\"}]",
                result.toStableJson());
    }

    @Test
    void evaluatesLeftJoinPaidFilterPreservesNullsFamily() {
        var result = evaluator.evaluate(
                JoinCaseModel.of(JoinProfile.INNER_JOIN_BASIC.id(), JoinCaseModel.Family.LEFT_JOIN_PAID_FILTER_PRESERVES_NULLS),
                FixtureCatalog.accountsFixture(),
                FixtureCatalog.plansFixture(),
                FixtureCatalog.planFamiliesFixture());

        assertEquals(
                "[{\"account_id\": 1, \"plan_name\": null}, {\"account_id\": 3, \"plan_name\": null}, {\"account_id\": 5, \"plan_name\": \"Enterprise\"}]",
                result.toStableJson());
    }

    @Test
    void evaluatesLeftJoinPaidWhereCollapsesNullExtensionFamily() {
        var result = evaluator.evaluate(
                JoinCaseModel.of(JoinProfile.INNER_JOIN_BASIC.id(), JoinCaseModel.Family.LEFT_JOIN_PAID_WHERE_COLLAPSES_NULL_EXTENSION),
                FixtureCatalog.accountsFixture(),
                FixtureCatalog.plansFixture(),
                FixtureCatalog.planFamiliesFixture());

        assertEquals(
                "[{\"account_id\": 5, \"plan_name\": \"Enterprise\"}]",
                result.toStableJson());
    }

    @Test
    void evaluatesTwoJoinActivePlanFamily() {
        var result = evaluator.evaluate(
                JoinCaseModel.of(JoinProfile.INNER_JOIN_BASIC.id(), JoinCaseModel.Family.TWO_JOIN_ACTIVE_PLAN_FAMILY),
                FixtureCatalog.accountsFixture(),
                FixtureCatalog.plansFixture(),
                FixtureCatalog.planFamiliesFixture());

        assertEquals(
                "[{\"account_id\": 1, \"plan_name\": \"Starter\"}, {\"account_id\": 5, \"plan_name\": \"Growth\"}]",
                result.toStableJson());
    }

    @Test
    void evaluatesTwoJoinDuplicateSecondHopMultiplicationFamily() {
        var result = evaluator.evaluate(
                JoinCaseModel.of(JoinProfile.INNER_JOIN_BASIC.id(), JoinCaseModel.Family.TWO_JOIN_DUPLICATE_SECOND_HOP_MULTIPLICATION),
                FixtureCatalog.accountsFixture(),
                FixtureCatalog.plansFixture(),
                FixtureCatalog.planFamiliesFixtureWithDuplicateGrowth());

        assertEquals(
                "[{\"account_id\": 1, \"plan_name\": \"Starter\"}, {\"account_id\": 5, \"plan_name\": \"Growth Plus\"}, {\"account_id\": 5, \"plan_name\": \"Growth\"}]",
                result.toStableJson());
    }

    @Test
    void evaluatesLeftJoinDuplicateNullExtensionFamily() {
        var result = evaluator.evaluate(
                JoinCaseModel.of(JoinProfile.INNER_JOIN_BASIC.id(), JoinCaseModel.Family.LEFT_JOIN_DUPLICATE_NULL_EXTENSION),
                FixtureCatalog.accountsFixture(),
                FixtureCatalog.plansFixtureWithDuplicateFree(),
                FixtureCatalog.planFamiliesFixture());

        assertEquals(
                "[{\"account_id\": 1, \"plan_name\": \"Free Plus\"}, {\"account_id\": 1, \"plan_name\": \"Free\"}, {\"account_id\": 3, \"plan_name\": null}, {\"account_id\": 5, \"plan_name\": \"Enterprise\"}]",
                result.toStableJson());
    }

    @Test
    void evaluatesLeftTwoJoinNullExtensionFamily() {
        var result = evaluator.evaluate(
                JoinCaseModel.of(JoinProfile.INNER_JOIN_BASIC.id(), JoinCaseModel.Family.LEFT_TWO_JOIN_NULL_EXTENSION),
                FixtureCatalog.accountsFixture(),
                FixtureCatalog.plansFixture(),
                FixtureCatalog.planFamiliesFixture());

        assertEquals(
                "[{\"account_id\": 1, \"plan_name\": \"Starter\"}, {\"account_id\": 3, \"plan_name\": null}, {\"account_id\": 5, \"plan_name\": \"Growth\"}]",
                result.toStableJson());
    }

    @Test
    void evaluatesLeftTwoJoinSecondHopFilterFamily() {
        var result = evaluator.evaluate(
                JoinCaseModel.of(JoinProfile.INNER_JOIN_BASIC.id(), JoinCaseModel.Family.LEFT_TWO_JOIN_SECOND_HOP_FILTER),
                FixtureCatalog.accountsFixture(),
                FixtureCatalog.plansFixture(),
                FixtureCatalog.planFamiliesFixture());

        assertEquals(
                "[{\"account_id\": 1, \"plan_name\": null}, {\"account_id\": 3, \"plan_name\": null}, {\"account_id\": 5, \"plan_name\": \"Growth\"}]",
                result.toStableJson());
    }

    @Test
    void evaluatesLeftTwoJoinSecondHopWhereCollapsesNullExtensionFamily() {
        var result = evaluator.evaluate(
                JoinCaseModel.of(JoinProfile.INNER_JOIN_BASIC.id(), JoinCaseModel.Family.LEFT_TWO_JOIN_SECOND_HOP_WHERE_COLLAPSES_NULL_EXTENSION),
                FixtureCatalog.accountsFixture(),
                FixtureCatalog.plansFixture(),
                FixtureCatalog.planFamiliesFixture());

        assertEquals(
                "[{\"account_id\": 5, \"plan_name\": \"Growth\"}]",
                result.toStableJson());
    }
}
