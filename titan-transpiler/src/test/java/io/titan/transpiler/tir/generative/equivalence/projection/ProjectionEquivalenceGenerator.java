package io.titan.transpiler.tir.generative.equivalence.projection;

import io.titan.transpiler.tir.generative.shared.SelectCaseModel;
import java.util.List;

final class ProjectionEquivalenceGenerator {

    ProjectionEquivalenceCaseModel.ProjectionEquivalenceCase generate(long seed) {
        ProjectionEquivalenceCaseModel.Family family = switch ((int) Math.floorMod(seed, 2)) {
            case 0 -> ProjectionEquivalenceCaseModel.Family.ACTIVE_ROWS_PROJECTION_REORDER;
            default -> ProjectionEquivalenceCaseModel.Family.NONNULL_EMAIL_PROJECTION_REORDER;
        };
        return switch (family) {
            case ACTIVE_ROWS_PROJECTION_REORDER -> build(
                    family,
                    activeFilter(),
                    List.of(id(), email(), planCode()),
                    List.of(planCode(), id(), email()));
            case NONNULL_EMAIL_PROJECTION_REORDER -> build(
                    family,
                    nonnullEmailFilter(),
                    List.of(id(), email(), loginCount()),
                    List.of(loginCount(), id(), email()));
        };
    }

    private static ProjectionEquivalenceCaseModel.ProjectionEquivalenceCase build(
            ProjectionEquivalenceCaseModel.Family family,
            SelectCaseModel.Predicate filter,
            List<SelectCaseModel.Projection> original,
            List<SelectCaseModel.Projection> reordered
    ) {
        return ProjectionEquivalenceCaseModel.of(
                ProjectionEquivalenceProfile.PROJECTION_REORDER.id(),
                family,
                SelectCaseModel.selectCase(
                        ProjectionEquivalenceProfile.PROJECTION_REORDER.id(),
                        SelectCaseModel.SourceTable.ACCOUNTS_FIXTURE,
                        original,
                        filter,
                        List.of(SelectCaseModel.orderBy("id", SelectCaseModel.ValueType.INTEGER, SelectCaseModel.SortDirection.ASC)),
                        SelectCaseModel.ResultShape.ROW_SET),
                SelectCaseModel.selectCase(
                        ProjectionEquivalenceProfile.PROJECTION_REORDER.id(),
                        SelectCaseModel.SourceTable.ACCOUNTS_FIXTURE,
                        reordered,
                        filter,
                        List.of(SelectCaseModel.orderBy("id", SelectCaseModel.ValueType.INTEGER, SelectCaseModel.SortDirection.ASC)),
                        SelectCaseModel.ResultShape.ROW_SET));
    }

    private static SelectCaseModel.Predicate activeFilter() {
        return SelectCaseModel.compare(
                SelectCaseModel.column("active", SelectCaseModel.ValueType.BOOLEAN),
                SelectCaseModel.ComparisonOperator.EQ,
                SelectCaseModel.boolLiteral(true));
    }

    private static SelectCaseModel.Predicate nonnullEmailFilter() {
        return SelectCaseModel.nullCheck(
                SelectCaseModel.column("email", SelectCaseModel.ValueType.TEXT),
                SelectCaseModel.NullCheckKind.IS_NOT_NULL);
    }

    private static SelectCaseModel.Projection id() {
        return SelectCaseModel.projection("id", SelectCaseModel.column("id", SelectCaseModel.ValueType.INTEGER));
    }

    private static SelectCaseModel.Projection email() {
        return SelectCaseModel.projection("email", SelectCaseModel.column("email", SelectCaseModel.ValueType.TEXT));
    }

    private static SelectCaseModel.Projection planCode() {
        return SelectCaseModel.projection("plan_code", SelectCaseModel.column("plan_code", SelectCaseModel.ValueType.TEXT));
    }

    private static SelectCaseModel.Projection loginCount() {
        return SelectCaseModel.projection("login_count", SelectCaseModel.column("login_count", SelectCaseModel.ValueType.INTEGER));
    }
}
