package io.titan.transpiler.tir.generative.differential;

import io.titan.transpiler.tir.generative.shared.FixtureCatalog;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class SubqueryDifferentialReferenceEvaluatorTest {

    private final SubqueryDifferentialReferenceEvaluator evaluator = new SubqueryDifferentialReferenceEvaluator();

    @Test
    void evaluatesExistsFamily() {
        var fixture = FixtureCatalog.accountsFixture();

        var existsResult = evaluator.evaluate(
                SubqueryDifferentialCaseModel.of(SubqueryDifferentialProfile.SUBQUERY_BASIC.id(), SubqueryDifferentialCaseModel.Family.EXISTS_ENTERPRISE),
                fixture);

        assertEquals(
                "[{\"id\": 1, \"plan_code\": \"free\"}, {\"id\": 2, \"plan_code\": \"pro\"}, {\"id\": 3, \"plan_code\": null}, {\"id\": 4, \"plan_code\": \"pro\"}, {\"id\": 5, \"plan_code\": \"enterprise\"}, {\"id\": 6, \"plan_code\": \"free\"}]",
                existsResult.toStableJson());
    }

    @Test
    void evaluatesNotExistsFamily() {
        var fixture = FixtureCatalog.accountsFixture();

        var notExistsResult = evaluator.evaluate(
                SubqueryDifferentialCaseModel.of(SubqueryDifferentialProfile.SUBQUERY_BASIC.id(), SubqueryDifferentialCaseModel.Family.NOT_EXISTS_VIP_ACTIVE),
                fixture);

        assertEquals(
                "[{\"email\": \"ada@titan.dev\", \"id\": 1}, {\"email\": \"drew@titan.dev\", \"id\": 5}, {\"email\": null, \"id\": 3}]",
                notExistsResult.toStableJson());
    }

    @Test
    void evaluatesCorrelatedExistsFamily() {
        var fixture = FixtureCatalog.accountsFixture();

        var correlatedResult = evaluator.evaluate(
                SubqueryDifferentialCaseModel.of(SubqueryDifferentialProfile.SUBQUERY_BASIC.id(), SubqueryDifferentialCaseModel.Family.CORRELATED_EXISTS_ACTIVE_PLAN),
                fixture);

        assertEquals(
                "[{\"email\": \"ada@titan.dev\", \"id\": 1}, {\"email\": \"drew@titan.dev\", \"id\": 5}]",
                correlatedResult.toStableJson());
    }

    @Test
    void evaluatesCorrelatedNotExistsFamily() {
        var fixture = FixtureCatalog.accountsFixture();

        var correlatedResult = evaluator.evaluate(
                SubqueryDifferentialCaseModel.of(SubqueryDifferentialProfile.SUBQUERY_BASIC.id(), SubqueryDifferentialCaseModel.Family.CORRELATED_NOT_EXISTS_NULL_PLAN),
                fixture);

        assertEquals(
                "[{\"email\": null, \"id\": 3}]",
                correlatedResult.toStableJson());
    }

    @Test
    void evaluatesCorrelatedScalarFamily() {
        var fixture = FixtureCatalog.accountsFixture();

        var correlatedScalarResult = evaluator.evaluate(
                SubqueryDifferentialCaseModel.of(SubqueryDifferentialProfile.SUBQUERY_BASIC.id(), SubqueryDifferentialCaseModel.Family.CORRELATED_SCALAR_EMAIL_BY_PLAN),
                fixture);

        assertEquals(
                "[{\"email\": \"ada@titan.dev\", \"id\": 1}, {\"email\": \"drew@titan.dev\", \"id\": 5}]",
                correlatedScalarResult.toStableJson());
    }

    @Test
    void evaluatesCorrelatedScalarNullOrAbsentFamily() {
        var fixture = FixtureCatalog.accountsFixture();

        var correlatedScalarResult = evaluator.evaluate(
                SubqueryDifferentialCaseModel.of(SubqueryDifferentialProfile.SUBQUERY_BASIC.id(), SubqueryDifferentialCaseModel.Family.CORRELATED_SCALAR_NULL_OR_ABSENT_EMAIL_BY_PLAN),
                fixture);

        assertEquals(
                "[{\"email\": \"ada@titan.dev\", \"id\": 1}, {\"email\": \"drew@titan.dev\", \"id\": 5}]",
                correlatedScalarResult.toStableJson());
    }

    @Test
    void evaluatesScalarLookupFamily() {
        var fixture = FixtureCatalog.accountsFixture();

        var scalarResult = evaluator.evaluate(
                SubqueryDifferentialCaseModel.of(SubqueryDifferentialProfile.SUBQUERY_BASIC.id(), SubqueryDifferentialCaseModel.Family.SCALAR_EMAIL_LOOKUP),
                fixture);

        assertEquals(
                "[{\"email\": \"ada@titan.dev\", \"id\": 1}]",
                scalarResult.toStableJson());
    }

    @Test
    void evaluatesScalarNullLookupFamily() {
        var fixture = FixtureCatalog.accountsFixture();

        var scalarResult = evaluator.evaluate(
                SubqueryDifferentialCaseModel.of(SubqueryDifferentialProfile.SUBQUERY_BASIC.id(), SubqueryDifferentialCaseModel.Family.SCALAR_NULL_EMAIL_LOOKUP),
                fixture);

        assertEquals(
                "[]",
                scalarResult.toStableJson());
    }

    @Test
    void evaluatesCteFamily() {
        var fixture = FixtureCatalog.accountsFixture();

        var cteResult = evaluator.evaluate(
                SubqueryDifferentialCaseModel.of(SubqueryDifferentialProfile.SUBQUERY_BASIC.id(), SubqueryDifferentialCaseModel.Family.CTE_ACTIVE_ROWS),
                fixture);

        assertEquals(
                "[{\"email\": \"ada@titan.dev\", \"id\": 1}, {\"email\": \"drew@titan.dev\", \"id\": 5}, {\"email\": null, \"id\": 3}]",
                cteResult.toStableJson());
    }
}
