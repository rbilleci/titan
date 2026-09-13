package io.titan.transpiler.tir.generative.equivalence.outerjoin;

import io.titan.transpiler.tir.generative.shared.JoinCaseModel;
import java.util.Objects;

final class OuterJoinEquivalenceCaseModel {

    enum Family {
        LEFT_JOIN_NULL_EXTENSION_EQCOLUMN_VS_PAIR("left-join-null-extension-eqcolumn-vs-pair"),
        LEFT_JOIN_DUPLICATE_NULL_EXTENSION_EQCOLUMN_VS_PAIR("left-join-duplicate-null-extension-eqcolumn-vs-pair"),
        LEFT_TWO_HOP_FILTERED_SECOND_HOP_EQCOLUMN_VS_FILTERED_PAIR("left-two-hop-filtered-second-hop-eqcolumn-vs-filtered-pair");

        private final String id;

        Family(String id) {
            this.id = id;
        }

        String id() {
            return id;
        }
    }

    record OuterJoinEquivalenceCase(
            String profileId,
            Family family,
            JoinCaseModel.JoinCase referenceCase,
            String explicitJavaSource,
            String helperJavaSource
    ) {
        OuterJoinEquivalenceCase {
            if (profileId == null || profileId.isBlank()) {
                throw new IllegalArgumentException("profileId must not be blank");
            }
            Objects.requireNonNull(family, "family");
            Objects.requireNonNull(referenceCase, "referenceCase");
            Objects.requireNonNull(explicitJavaSource, "explicitJavaSource");
            Objects.requireNonNull(helperJavaSource, "helperJavaSource");
        }
    }

    static OuterJoinEquivalenceCase of(
            String profileId,
            Family family,
            JoinCaseModel.JoinCase referenceCase,
            String explicitJavaSource,
            String helperJavaSource
    ) {
        return new OuterJoinEquivalenceCase(profileId, family, referenceCase, explicitJavaSource, helperJavaSource);
    }
}
