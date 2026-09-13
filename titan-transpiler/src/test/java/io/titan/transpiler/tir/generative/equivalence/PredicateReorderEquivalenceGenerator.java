package io.titan.transpiler.tir.generative.equivalence;

import io.titan.transpiler.tir.generative.shared.SelectCaseModel;
import java.util.List;

final class PredicateReorderEquivalenceGenerator {

    PredicateReorderEquivalenceCaseModel.EquivalenceCase generate(long seed) {
        PredicateReorderEquivalenceCaseModel.Family family = switch ((int) Math.floorMod(seed, 3)) {
            case 0 -> PredicateReorderEquivalenceCaseModel.Family.COMMUTATIVE_AND_REORDER;
            case 1 -> PredicateReorderEquivalenceCaseModel.Family.AND_OUTER_CHILD_REORDER;
            default -> PredicateReorderEquivalenceCaseModel.Family.OR_CHILD_REORDER_UNDER_AND;
        };
        return switch (family) {
            case COMMUTATIVE_AND_REORDER -> PredicateReorderEquivalenceCaseModel.of(
                    PredicateReorderEquivalenceProfile.PREDICATE_REORDER.id(),
                    family,
                    SelectCaseModel.selectCase(
                            PredicateReorderEquivalenceProfile.PREDICATE_REORDER.id(),
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
                            List.of(SelectCaseModel.orderBy("id", SelectCaseModel.ValueType.INTEGER, SelectCaseModel.SortDirection.ASC)),
                            SelectCaseModel.ResultShape.ROW_SET),
                    SelectCaseModel.selectCase(
                            PredicateReorderEquivalenceProfile.PREDICATE_REORDER.id(),
                            SelectCaseModel.SourceTable.ACCOUNTS_FIXTURE,
                            List.of(
                                    SelectCaseModel.projection("id", SelectCaseModel.column("id", SelectCaseModel.ValueType.INTEGER)),
                                    SelectCaseModel.projection("email", SelectCaseModel.column("email", SelectCaseModel.ValueType.TEXT))),
                            SelectCaseModel.and(
                                    SelectCaseModel.nullCheck(
                                            SelectCaseModel.column("email", SelectCaseModel.ValueType.TEXT),
                                            SelectCaseModel.NullCheckKind.IS_NOT_NULL),
                                    SelectCaseModel.compare(
                                            SelectCaseModel.column("active", SelectCaseModel.ValueType.BOOLEAN),
                                            SelectCaseModel.ComparisonOperator.EQ,
                                            SelectCaseModel.boolLiteral(true))),
                            List.of(SelectCaseModel.orderBy("id", SelectCaseModel.ValueType.INTEGER, SelectCaseModel.SortDirection.ASC)),
                            SelectCaseModel.ResultShape.ROW_SET));
            case AND_OUTER_CHILD_REORDER -> PredicateReorderEquivalenceCaseModel.of(
                    PredicateReorderEquivalenceProfile.PREDICATE_REORDER.id(),
                    family,
                    SelectCaseModel.selectCase(
                            PredicateReorderEquivalenceProfile.PREDICATE_REORDER.id(),
                            SelectCaseModel.SourceTable.ACCOUNTS_FIXTURE,
                            List.of(
                                    SelectCaseModel.projection("id", SelectCaseModel.column("id", SelectCaseModel.ValueType.INTEGER)),
                                    SelectCaseModel.projection("login_count", SelectCaseModel.column("login_count", SelectCaseModel.ValueType.INTEGER))),
                            SelectCaseModel.and(
                                    SelectCaseModel.nullCheck(
                                            SelectCaseModel.column("login_count", SelectCaseModel.ValueType.INTEGER),
                                            SelectCaseModel.NullCheckKind.IS_NOT_NULL),
                                    SelectCaseModel.and(
                                            SelectCaseModel.compare(
                                                    SelectCaseModel.column("id", SelectCaseModel.ValueType.INTEGER),
                                                    SelectCaseModel.ComparisonOperator.GT,
                                                    SelectCaseModel.intLiteral(1)),
                                            SelectCaseModel.compare(
                                                    SelectCaseModel.column("id", SelectCaseModel.ValueType.INTEGER),
                                                    SelectCaseModel.ComparisonOperator.LE,
                                                    SelectCaseModel.intLiteral(5)))),
                            List.of(SelectCaseModel.orderBy("id", SelectCaseModel.ValueType.INTEGER, SelectCaseModel.SortDirection.ASC)),
                            SelectCaseModel.ResultShape.ROW_SET),
                    SelectCaseModel.selectCase(
                            PredicateReorderEquivalenceProfile.PREDICATE_REORDER.id(),
                            SelectCaseModel.SourceTable.ACCOUNTS_FIXTURE,
                            List.of(
                                    SelectCaseModel.projection("id", SelectCaseModel.column("id", SelectCaseModel.ValueType.INTEGER)),
                                    SelectCaseModel.projection("login_count", SelectCaseModel.column("login_count", SelectCaseModel.ValueType.INTEGER))),
                            SelectCaseModel.and(
                                    SelectCaseModel.and(
                                            SelectCaseModel.compare(
                                                    SelectCaseModel.column("id", SelectCaseModel.ValueType.INTEGER),
                                                    SelectCaseModel.ComparisonOperator.GT,
                                                    SelectCaseModel.intLiteral(1)),
                                            SelectCaseModel.compare(
                                                    SelectCaseModel.column("id", SelectCaseModel.ValueType.INTEGER),
                                                    SelectCaseModel.ComparisonOperator.LE,
                                                    SelectCaseModel.intLiteral(5))),
                                    SelectCaseModel.nullCheck(
                                            SelectCaseModel.column("login_count", SelectCaseModel.ValueType.INTEGER),
                                            SelectCaseModel.NullCheckKind.IS_NOT_NULL)),
                            List.of(SelectCaseModel.orderBy("id", SelectCaseModel.ValueType.INTEGER, SelectCaseModel.SortDirection.ASC)),
                            SelectCaseModel.ResultShape.ROW_SET));
            case OR_CHILD_REORDER_UNDER_AND -> PredicateReorderEquivalenceCaseModel.of(
                    PredicateReorderEquivalenceProfile.PREDICATE_REORDER.id(),
                    family,
                    SelectCaseModel.selectCase(
                            PredicateReorderEquivalenceProfile.PREDICATE_REORDER.id(),
                            SelectCaseModel.SourceTable.ACCOUNTS_FIXTURE,
                            List.of(
                                    SelectCaseModel.projection("id", SelectCaseModel.column("id", SelectCaseModel.ValueType.INTEGER)),
                                    SelectCaseModel.projection("plan_code", SelectCaseModel.column("plan_code", SelectCaseModel.ValueType.TEXT))),
                            SelectCaseModel.and(
                                    SelectCaseModel.nullCheck(
                                            SelectCaseModel.column("plan_code", SelectCaseModel.ValueType.TEXT),
                                            SelectCaseModel.NullCheckKind.IS_NOT_NULL),
                                    SelectCaseModel.or(
                                            SelectCaseModel.compare(
                                                    SelectCaseModel.column("id", SelectCaseModel.ValueType.INTEGER),
                                                    SelectCaseModel.ComparisonOperator.EQ,
                                                    SelectCaseModel.intLiteral(4)),
                                            SelectCaseModel.compare(
                                                    SelectCaseModel.column("id", SelectCaseModel.ValueType.INTEGER),
                                                    SelectCaseModel.ComparisonOperator.EQ,
                                                    SelectCaseModel.intLiteral(5)))),
                            List.of(SelectCaseModel.orderBy("id", SelectCaseModel.ValueType.INTEGER, SelectCaseModel.SortDirection.ASC)),
                            SelectCaseModel.ResultShape.ROW_SET),
                    SelectCaseModel.selectCase(
                            PredicateReorderEquivalenceProfile.PREDICATE_REORDER.id(),
                            SelectCaseModel.SourceTable.ACCOUNTS_FIXTURE,
                            List.of(
                                    SelectCaseModel.projection("id", SelectCaseModel.column("id", SelectCaseModel.ValueType.INTEGER)),
                                    SelectCaseModel.projection("plan_code", SelectCaseModel.column("plan_code", SelectCaseModel.ValueType.TEXT))),
                            SelectCaseModel.and(
                                    SelectCaseModel.nullCheck(
                                            SelectCaseModel.column("plan_code", SelectCaseModel.ValueType.TEXT),
                                            SelectCaseModel.NullCheckKind.IS_NOT_NULL),
                                    SelectCaseModel.or(
                                            SelectCaseModel.compare(
                                                    SelectCaseModel.column("id", SelectCaseModel.ValueType.INTEGER),
                                                    SelectCaseModel.ComparisonOperator.EQ,
                                                    SelectCaseModel.intLiteral(5)),
                                            SelectCaseModel.compare(
                                                    SelectCaseModel.column("id", SelectCaseModel.ValueType.INTEGER),
                                                    SelectCaseModel.ComparisonOperator.EQ,
                                                    SelectCaseModel.intLiteral(4)))),
                            List.of(SelectCaseModel.orderBy("id", SelectCaseModel.ValueType.INTEGER, SelectCaseModel.SortDirection.ASC)),
                            SelectCaseModel.ResultShape.ROW_SET));
        };
    }
}
