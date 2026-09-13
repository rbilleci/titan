package io.titan.transpiler.tir.generative.mutation;

import io.titan.transpiler.tir.generative.shared.JoinCaseModel;
import java.util.List;
import java.util.Objects;

final class TwoHopJoinMutationCaseModel {

    enum Family {
        TWO_HOP_ACTIVE_SECOND_HOP_EQCOLUMN_TO_PAIR("two-hop-active-second-hop-eqcolumn-to-pair"),
        LEFT_TWO_HOP_NULL_EXTENSION_SECOND_HOP_EQCOLUMN_TO_PAIR("left-two-hop-null-extension-second-hop-eqcolumn-to-pair"),
        LEFT_TWO_HOP_FILTERED_SECOND_HOP_EQCOLUMN_TO_FILTERED_PAIR("left-two-hop-filtered-second-hop-eqcolumn-to-filtered-pair"),
        TWO_HOP_FILTERED_SECOND_HOP_EQCOLUMN_TO_FILTERED_PAIR("two-hop-filtered-second-hop-eqcolumn-to-filtered-pair");

        private final String id;

        Family(String id) {
            this.id = id;
        }

        String id() {
            return id;
        }
    }

    record TwoHopJoinMutationCase(
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
        TwoHopJoinMutationCase {
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

    static TwoHopJoinMutationCase of(
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
        return new TwoHopJoinMutationCase(
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
