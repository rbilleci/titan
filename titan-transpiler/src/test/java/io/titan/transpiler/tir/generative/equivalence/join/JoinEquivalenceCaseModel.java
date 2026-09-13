package io.titan.transpiler.tir.generative.equivalence.join;

import io.titan.transpiler.tir.generative.shared.JoinCaseModel;
import java.util.Objects;

final class JoinEquivalenceCaseModel {

    enum Family {
        INNER_JOIN_ACTIVE_ON_EQCOLUMN_VS_PAIR("inner-join-active-on-eqcolumn-vs-pair"),
        INNER_JOIN_PAID_ON_EQCOLUMN_VS_PAIR("inner-join-paid-on-eqcolumn-vs-pair"),
        TWO_HOP_FILTERED_SECOND_HOP_EQCOLUMN_VS_FILTERED_PAIR("two-hop-filtered-second-hop-eqcolumn-vs-filtered-pair");

        private final String id;

        Family(String id) {
            this.id = id;
        }

        String id() {
            return id;
        }
    }

    record JoinEquivalenceCase(
            String profileId,
            Family family,
            JoinCaseModel.JoinCase referenceCase,
            String explicitJavaSource,
            String helperJavaSource
    ) {
        JoinEquivalenceCase {
            if (profileId == null || profileId.isBlank()) {
                throw new IllegalArgumentException("profileId must not be blank");
            }
            Objects.requireNonNull(family, "family");
            Objects.requireNonNull(referenceCase, "referenceCase");
            Objects.requireNonNull(explicitJavaSource, "explicitJavaSource");
            Objects.requireNonNull(helperJavaSource, "helperJavaSource");
        }

        String toStableJson() {
            return """
                    {
                      "profileId": "%s",
                      "family": "%s",
                      "referenceCase": %s
                    }
                    """.formatted(profileId, family.id(), referenceCase.toStableJson());
        }
    }

    static JoinEquivalenceCase of(
            String profileId,
            Family family,
            JoinCaseModel.JoinCase referenceCase,
            String explicitJavaSource,
            String helperJavaSource
    ) {
        return new JoinEquivalenceCase(profileId, family, referenceCase, explicitJavaSource, helperJavaSource);
    }
}
