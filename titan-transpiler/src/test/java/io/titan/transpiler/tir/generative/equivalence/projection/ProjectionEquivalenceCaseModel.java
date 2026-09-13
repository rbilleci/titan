package io.titan.transpiler.tir.generative.equivalence.projection;

import io.titan.transpiler.tir.generative.shared.SelectCaseModel;
import java.util.Objects;

final class ProjectionEquivalenceCaseModel {

    enum Family {
        ACTIVE_ROWS_PROJECTION_REORDER("active-rows-projection-reorder"),
        NONNULL_EMAIL_PROJECTION_REORDER("nonnull-email-projection-reorder");

        private final String id;

        Family(String id) {
            this.id = id;
        }

        String id() {
            return id;
        }
    }

    record ProjectionEquivalenceCase(
            String profileId,
            Family family,
            SelectCaseModel.SelectCase originalCase,
            SelectCaseModel.SelectCase reorderedCase
    ) {
        ProjectionEquivalenceCase {
            if (profileId == null || profileId.isBlank()) {
                throw new IllegalArgumentException("profileId must not be blank");
            }
            Objects.requireNonNull(family, "family");
            Objects.requireNonNull(originalCase, "originalCase");
            Objects.requireNonNull(reorderedCase, "reorderedCase");
        }

        String toStableJson() {
            return """
                    {
                      "profileId": "%s",
                      "family": "%s",
                      "originalCase": %s,
                      "reorderedCase": %s
                    }
                    """.formatted(profileId, family.id(), originalCase.toStableJson(), reorderedCase.toStableJson());
        }
    }

    static ProjectionEquivalenceCase of(
            String profileId,
            Family family,
            SelectCaseModel.SelectCase originalCase,
            SelectCaseModel.SelectCase reorderedCase
    ) {
        return new ProjectionEquivalenceCase(profileId, family, originalCase, reorderedCase);
    }
}
