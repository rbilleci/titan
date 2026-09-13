package io.titan.transpiler.tir.generative.equivalence;

import io.titan.transpiler.tir.generative.shared.SelectCaseModel;
import java.util.Objects;

final class FormEquivalenceCaseModel {

    enum Family {
        SELECT_FROM_ACTIVE_ROWS("select-from-active-rows"),
        SELECT_FROM_NONNULL_EMAIL("select-from-nonnull-email"),
        SELECT_FROM_NULL_EMAIL_ROWS("select-from-null-email-rows");

        private final String id;

        Family(String id) {
            this.id = id;
        }

        String id() {
            return id;
        }
    }

    record FormEquivalenceCase(
            String profileId,
            Family family,
            SelectCaseModel.SelectCase referenceCase,
            String helperJavaSource,
            String explicitJavaSource
    ) {
        FormEquivalenceCase {
            if (profileId == null || profileId.isBlank()) {
                throw new IllegalArgumentException("profileId must not be blank");
            }
            Objects.requireNonNull(family, "family");
            Objects.requireNonNull(referenceCase, "referenceCase");
            Objects.requireNonNull(helperJavaSource, "helperJavaSource");
            Objects.requireNonNull(explicitJavaSource, "explicitJavaSource");
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

    static FormEquivalenceCase of(
            String profileId,
            Family family,
            SelectCaseModel.SelectCase referenceCase,
            String helperJavaSource,
            String explicitJavaSource
    ) {
        return new FormEquivalenceCase(profileId, family, referenceCase, helperJavaSource, explicitJavaSource);
    }
}
