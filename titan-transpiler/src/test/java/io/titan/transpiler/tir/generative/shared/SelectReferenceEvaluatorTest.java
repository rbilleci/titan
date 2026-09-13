package io.titan.transpiler.tir.generative.shared;

import io.titan.transpiler.tir.generative.shared.SelectReferenceEvaluator;
import io.titan.transpiler.tir.generative.shared.SelectCaseModel;
import io.titan.transpiler.tir.generative.shared.ReferenceResultModels.ReferenceResult;
import io.titan.transpiler.tir.generative.shared.FixtureCatalog;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class SelectReferenceEvaluatorTest {

    private final SelectReferenceEvaluator evaluator = new SelectReferenceEvaluator();

    @Test
    void evaluatesBasicFilterProjectionCaseAgainstFixtureRows() {
        SelectCaseModel.SelectCase selectCase = SelectCaseModel.selectCase(
                "transpiler-diff-basic-select",
                SelectCaseModel.SourceTable.ACCOUNTS_FIXTURE,
                List.of(
                        SelectCaseModel.projection("id", SelectCaseModel.column("id", SelectCaseModel.ValueType.INTEGER)),
                        SelectCaseModel.projection("email", SelectCaseModel.column("email", SelectCaseModel.ValueType.TEXT))),
                SelectCaseModel.and(
                        SelectCaseModel.compare(
                                SelectCaseModel.column("active", SelectCaseModel.ValueType.BOOLEAN),
                                SelectCaseModel.ComparisonOperator.EQ,
                                SelectCaseModel.boolLiteral(true)),
                        SelectCaseModel.nullCheck(
                                SelectCaseModel.column("email", SelectCaseModel.ValueType.TEXT),
                                SelectCaseModel.NullCheckKind.IS_NOT_NULL)),
                List.of(SelectCaseModel.orderBy(
                        "id",
                        SelectCaseModel.ValueType.INTEGER,
                        SelectCaseModel.SortDirection.ASC)),
                SelectCaseModel.ResultShape.ROW_SET);

        ReferenceResult result = evaluator.evaluate(
                selectCase,
                FixtureCatalog.accountsFixture());

        assertEquals(
                "[{\"email\": \"ada@titan.dev\", \"id\": 1}, {\"email\": \"drew@titan.dev\", \"id\": 5}]",
                result.toStableJson());
    }

    @Test
    void evaluatesArithmeticProjectionAndNullSemantics() {
        SelectCaseModel.SelectCase selectCase = SelectCaseModel.selectCase(
                "transpiler-diff-basic-select",
                SelectCaseModel.SourceTable.ACCOUNTS_FIXTURE,
                List.of(
                        SelectCaseModel.projection("id", SelectCaseModel.column("id", SelectCaseModel.ValueType.INTEGER)),
                        SelectCaseModel.projection(
                                "login_plus_one",
                                SelectCaseModel.add(
                                        SelectCaseModel.column("login_count", SelectCaseModel.ValueType.INTEGER),
                                        SelectCaseModel.intLiteral(1)))),
                SelectCaseModel.compare(
                        SelectCaseModel.column("id", SelectCaseModel.ValueType.INTEGER),
                        SelectCaseModel.ComparisonOperator.GE,
                        SelectCaseModel.intLiteral(4)),
                List.of(),
                SelectCaseModel.ResultShape.ROW_SET);

        ReferenceResult result = evaluator.evaluate(
                selectCase,
                FixtureCatalog.accountsFixture());

        assertEquals(
                "[{\"id\": 4, \"login_plus_one\": null}, {\"id\": 5, \"login_plus_one\": 13}, {\"id\": 6, \"login_plus_one\": 1}]",
                result.toStableJson());
    }

    @Test
    void normalizesUnorderedResultsDeterministically() {
        SelectCaseModel.SelectCase selectCase = SelectCaseModel.selectCase(
                "transpiler-diff-basic-select",
                SelectCaseModel.SourceTable.ACCOUNTS_FIXTURE,
                List.of(SelectCaseModel.projection("plan_code", SelectCaseModel.column("plan_code", SelectCaseModel.ValueType.TEXT))),
                SelectCaseModel.or(
                        SelectCaseModel.compare(
                                SelectCaseModel.column("id", SelectCaseModel.ValueType.INTEGER),
                                SelectCaseModel.ComparisonOperator.EQ,
                                SelectCaseModel.intLiteral(2)),
                        SelectCaseModel.compare(
                                SelectCaseModel.column("id", SelectCaseModel.ValueType.INTEGER),
                                SelectCaseModel.ComparisonOperator.EQ,
                                SelectCaseModel.intLiteral(1))),
                List.of(),
                SelectCaseModel.ResultShape.ROW_SET);

        ReferenceResult result = evaluator.evaluate(
                selectCase,
                FixtureCatalog.accountsFixture());

        assertEquals(result.toStableJson(), evaluator.evaluate(selectCase, FixtureCatalog.accountsFixture()).toStableJson());
        assertTrue(result.toStableJson().contains("{\"plan_code\": \"free\"}"));
        assertTrue(result.toStableJson().contains("{\"plan_code\": \"pro\"}"));
    }

    @Test
    void appliesSqlThreeValuedLogicForNotAndNullableComparisons() {
        SelectCaseModel.SelectCase selectCase = SelectCaseModel.selectCase(
                "transpiler-diff-basic-select",
                SelectCaseModel.SourceTable.ACCOUNTS_FIXTURE,
                List.of(
                        SelectCaseModel.projection("id", SelectCaseModel.column("id", SelectCaseModel.ValueType.INTEGER)),
                        SelectCaseModel.projection("active", SelectCaseModel.column("active", SelectCaseModel.ValueType.BOOLEAN))),
                SelectCaseModel.not(
                        SelectCaseModel.compare(
                                SelectCaseModel.column("active", SelectCaseModel.ValueType.BOOLEAN),
                                SelectCaseModel.ComparisonOperator.EQ,
                                SelectCaseModel.boolLiteral(true))),
                List.of(SelectCaseModel.orderBy(
                        "id",
                        SelectCaseModel.ValueType.INTEGER,
                        SelectCaseModel.SortDirection.ASC)),
                SelectCaseModel.ResultShape.ROW_SET);

        ReferenceResult result = evaluator.evaluate(
                selectCase,
                FixtureCatalog.accountsFixture());

        assertEquals(
                "[{\"active\": false, \"id\": 2}, {\"active\": false, \"id\": 6}]",
                result.toStableJson());
    }

    @Test
    void preservesNullPropagationForNullableArithmeticProjections() {
        SelectCaseModel.SelectCase selectCase = SelectCaseModel.selectCase(
                "transpiler-diff-basic-select",
                SelectCaseModel.SourceTable.ACCOUNTS_FIXTURE,
                List.of(
                        SelectCaseModel.projection("id", SelectCaseModel.column("id", SelectCaseModel.ValueType.INTEGER)),
                        SelectCaseModel.projection(
                                "login_plus_one",
                                SelectCaseModel.add(
                                        SelectCaseModel.column("login_count", SelectCaseModel.ValueType.INTEGER),
                                        SelectCaseModel.intLiteral(1)))),
                SelectCaseModel.compare(
                        SelectCaseModel.column("id", SelectCaseModel.ValueType.INTEGER),
                        SelectCaseModel.ComparisonOperator.GE,
                        SelectCaseModel.intLiteral(4)),
                List.of(SelectCaseModel.orderBy(
                        "id",
                        SelectCaseModel.ValueType.INTEGER,
                        SelectCaseModel.SortDirection.ASC)),
                SelectCaseModel.ResultShape.ROW_SET);

        ReferenceResult result = evaluator.evaluate(
                selectCase,
                FixtureCatalog.accountsFixture());

        assertEquals(
                "[{\"id\": 4, \"login_plus_one\": null}, {\"id\": 5, \"login_plus_one\": 13}, {\"id\": 6, \"login_plus_one\": 1}]",
                result.toStableJson());
    }
}
