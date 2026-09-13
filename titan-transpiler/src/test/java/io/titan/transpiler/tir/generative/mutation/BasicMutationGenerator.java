package io.titan.transpiler.tir.generative.mutation;

import io.titan.transpiler.tir.generative.differential.SelectDifferentialGenerator;
import io.titan.transpiler.tir.generative.shared.SelectCaseModel;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

final class BasicMutationGenerator {

    MutationCaseModel.MutationCase generate(MutationProfile profile, long seed) {
        return generate(profile, seed, null);
    }

    MutationCaseModel.MutationCase generate(MutationProfile profile, long seed, MutationCaseModel.MutatorId mutatorOverride) {
        Objects.requireNonNull(profile, "profile");
        return switch (profile) {
            case BASIC -> generateBasic(seed, mutatorOverride);
            case JOINS, SUBQUERIES -> throw new IllegalArgumentException("Phase D mutation profile not implemented yet: " + profile.id());
        };
    }

    private static MutationCaseModel.MutationCase generateBasic(long seed, MutationCaseModel.MutatorId mutatorOverride) {
        SelectCaseModel.SelectCase baseCase = new SelectDifferentialGenerator().generate(seed);
        MutationCaseModel.MutatorId mutator = mutatorOverride == null
                ? BASIC_MUTATORS[(int) Math.floorMod(seed, BASIC_MUTATORS.length)]
                : mutatorOverride;
        return switch (mutator) {
            case ADD_SAFE_CONJUNCT -> addSafeConjunct(seed, baseCase);
            case ADD_SAFE_DISJUNCT -> addSafeDisjunct(seed, baseCase);
            case DUPLICATE_PROJECTION -> duplicateProjection(seed, baseCase);
            case INJECT_DERIVED_PROJECTION -> throw new IllegalArgumentException(
                    "Phase D mutator not implemented yet for basic profile: " + mutator.id());
        };
    }

    private static final MutationCaseModel.MutatorId[] BASIC_MUTATORS = {
            MutationCaseModel.MutatorId.ADD_SAFE_CONJUNCT,
            MutationCaseModel.MutatorId.ADD_SAFE_DISJUNCT,
            MutationCaseModel.MutatorId.DUPLICATE_PROJECTION
    };

    private static MutationCaseModel.MutationCase addSafeConjunct(long seed, SelectCaseModel.SelectCase baseCase) {
        SelectCaseModel.Predicate addedPredicate = SelectCaseModel.compare(
                SelectCaseModel.column("id", SelectCaseModel.ValueType.INTEGER),
                SelectCaseModel.ComparisonOperator.GE,
                SelectCaseModel.intLiteral(1));
        SelectCaseModel.Predicate mutatedFilter = baseCase.filter() == null
                ? addedPredicate
                : SelectCaseModel.and(baseCase.filter(), addedPredicate);
        SelectCaseModel.SelectCase mutatedCase = cloneSelectCase(baseCase, mutatedFilter, baseCase.projections());
        return MutationCaseModel.mutationCase(
                MutationProfile.BASIC.id(),
                baseCase.profileId(),
                seed,
                MutationCaseModel.MutatorId.ADD_SAFE_CONJUNCT,
                MutationCaseModel.parameterSet(
                        MutationCaseModel.parameter("addedColumn", "id"),
                        MutationCaseModel.parameter("operator", ">="),
                        MutationCaseModel.parameter("literal", "1")),
                baseCase,
                mutatedCase,
                MutationCaseModel.MutationClassification.STABLE_PASS,
                List.of());
    }

    private static MutationCaseModel.MutationCase addSafeDisjunct(long seed, SelectCaseModel.SelectCase baseCase) {
        SelectCaseModel.Predicate addedPredicate = SelectCaseModel.compare(
                SelectCaseModel.column("id", SelectCaseModel.ValueType.INTEGER),
                SelectCaseModel.ComparisonOperator.EQ,
                SelectCaseModel.intLiteral(1));
        SelectCaseModel.Predicate mutatedFilter = baseCase.filter() == null
                ? addedPredicate
                : SelectCaseModel.or(baseCase.filter(), addedPredicate);
        SelectCaseModel.SelectCase mutatedCase = cloneSelectCase(baseCase, mutatedFilter, baseCase.projections());
        return MutationCaseModel.mutationCase(
                MutationProfile.BASIC.id(),
                baseCase.profileId(),
                seed,
                MutationCaseModel.MutatorId.ADD_SAFE_DISJUNCT,
                MutationCaseModel.parameterSet(
                        MutationCaseModel.parameter("addedColumn", "id"),
                        MutationCaseModel.parameter("operator", "="),
                        MutationCaseModel.parameter("literal", "1")),
                baseCase,
                mutatedCase,
                MutationCaseModel.MutationClassification.STABLE_PASS,
                List.of());
    }

    private static MutationCaseModel.MutationCase duplicateProjection(long seed, SelectCaseModel.SelectCase baseCase) {
        List<SelectCaseModel.Projection> projections = new ArrayList<>(baseCase.projections());
        SelectCaseModel.Projection original = baseCase.projections().getFirst();
        projections.add(SelectCaseModel.projection(original.alias() + "_dup", original.expression()));
        SelectCaseModel.SelectCase mutatedCase = cloneSelectCase(baseCase, baseCase.filter(), projections);
        return MutationCaseModel.mutationCase(
                MutationProfile.BASIC.id(),
                baseCase.profileId(),
                seed,
                MutationCaseModel.MutatorId.DUPLICATE_PROJECTION,
                MutationCaseModel.parameterSet(
                        MutationCaseModel.parameter("sourceAlias", original.alias()),
                        MutationCaseModel.parameter("duplicateAlias", original.alias() + "_dup")),
                baseCase,
                mutatedCase,
                MutationCaseModel.MutationClassification.STABLE_PASS,
                List.of());
    }

    private static SelectCaseModel.SelectCase cloneSelectCase(
            SelectCaseModel.SelectCase baseCase,
            SelectCaseModel.Predicate filter,
            List<SelectCaseModel.Projection> projections
    ) {
        return SelectCaseModel.selectCase(
                MutationProfile.BASIC.id(),
                baseCase.sourceTable(),
                projections,
                filter,
                baseCase.ordering(),
                baseCase.resultShape());
    }
}
