package io.titan.transpiler.tir.generative.mutation;

import io.titan.transpiler.tir.generative.shared.JoinCaseModel;
import java.util.List;
import java.util.Objects;

final class OuterJoinMutationCaseModel {

    enum Family {
        LEFT_JOIN_NULL_EXTENSION_EQCOLUMN_TO_PAIR("left-join-null-extension-eqcolumn-to-pair"),
        LEFT_JOIN_DUPLICATE_NULL_EXTENSION_EQCOLUMN_TO_PAIR("left-join-duplicate-null-extension-eqcolumn-to-pair");

        private final String id;

        Family(String id) {
            this.id = id;
        }

        String id() {
            return id;
        }
    }

    record OuterJoinMutationCase(
            String profileId,
            Family family,
            JoinCaseModel.JoinCase baseCase,
            MutationCaseModel.MutatorId mutatorId,
            MutationCaseModel.MutationParameterSet mutationParameters,
            String baseJavaSource,
            String mutatedJavaSource,
            MutationCaseModel.MutationClassification classification,
            List<MutationCaseModel.ReductionStep> reductionSteps
    ) {
        OuterJoinMutationCase {
            if (profileId == null || profileId.isBlank()) {
                throw new IllegalArgumentException("profileId must not be blank");
            }
            Objects.requireNonNull(family, "family");
            Objects.requireNonNull(baseCase, "baseCase");
            Objects.requireNonNull(mutatorId, "mutatorId");
            Objects.requireNonNull(mutationParameters, "mutationParameters");
            Objects.requireNonNull(baseJavaSource, "baseJavaSource");
            Objects.requireNonNull(mutatedJavaSource, "mutatedJavaSource");
            Objects.requireNonNull(classification, "classification");
            reductionSteps = List.copyOf(Objects.requireNonNull(reductionSteps, "reductionSteps"));
            if (reductionSteps.stream().anyMatch(Objects::isNull)) {
                throw new IllegalArgumentException("reductionSteps must not contain null");
            }
        }

        String toStableJson() {
            return """
                    {
                      "profileId": "%s",
                      "family": "%s",
                      "baseCase": %s,
                      "mutatorId": "%s",
                      "mutationParameters": %s,
                      "classification": "%s"
                    }
                    """.formatted(
                    profileId,
                    family.id(),
                    baseCase.toStableJson(),
                    mutatorId.id(),
                    mutationParameters.toStableJson(),
                    classification.id());
        }
    }

    static OuterJoinMutationCase of(
            String profileId,
            Family family,
            JoinCaseModel.JoinCase baseCase,
            MutationCaseModel.MutatorId mutatorId,
            MutationCaseModel.MutationParameterSet mutationParameters,
            String baseJavaSource,
            String mutatedJavaSource,
            MutationCaseModel.MutationClassification classification,
            List<MutationCaseModel.ReductionStep> reductionSteps
    ) {
        return new OuterJoinMutationCase(
                profileId,
                family,
                baseCase,
                mutatorId,
                mutationParameters,
                baseJavaSource,
                mutatedJavaSource,
                classification,
                reductionSteps);
    }
}
