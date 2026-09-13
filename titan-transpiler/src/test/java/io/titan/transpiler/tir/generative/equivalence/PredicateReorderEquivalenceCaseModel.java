package io.titan.transpiler.tir.generative.equivalence;

import io.titan.transpiler.tir.generative.shared.SelectCaseModel;
import java.util.Objects;

final class PredicateReorderEquivalenceCaseModel {

    enum Family {
        COMMUTATIVE_AND_REORDER("commutative-and-reorder"),
        AND_OUTER_CHILD_REORDER("and-outer-child-reorder"),
        OR_CHILD_REORDER_UNDER_AND("or-child-reorder-under-and");

        private final String id;

        Family(String id) {
            this.id = id;
        }

        String id() {
            return id;
        }
    }

    record EquivalenceCase(
            String profileId,
            Family family,
            SelectCaseModel.SelectCase originalCase,
            SelectCaseModel.SelectCase rewrittenCase
    ) {
        EquivalenceCase {
            if (profileId == null || profileId.isBlank()) {
                throw new IllegalArgumentException("profileId must not be blank");
            }
            Objects.requireNonNull(family, "family");
            Objects.requireNonNull(originalCase, "originalCase");
            Objects.requireNonNull(rewrittenCase, "rewrittenCase");
        }

        String toStableJson() {
            return """
                    {
                      "profileId": "%s",
                      "family": "%s",
                      "originalCase": %s,
                      "rewrittenCase": %s
                    }
                    """.formatted(profileId, family.id(), originalCase.toStableJson(), rewrittenCase.toStableJson());
        }
    }

    static EquivalenceCase of(
            String profileId,
            Family family,
            SelectCaseModel.SelectCase originalCase,
            SelectCaseModel.SelectCase rewrittenCase
    ) {
        return new EquivalenceCase(profileId, family, originalCase, rewrittenCase);
    }
}
