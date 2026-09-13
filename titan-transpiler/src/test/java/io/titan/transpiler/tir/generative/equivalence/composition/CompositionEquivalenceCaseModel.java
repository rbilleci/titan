package io.titan.transpiler.tir.generative.equivalence.composition;

import io.titan.transpiler.tir.generative.shared.SelectCaseModel;
import java.util.Objects;

final class CompositionEquivalenceCaseModel {

    enum Family {
        ACTIVE_ROWS_CTE_INLINE_VIEW("active-rows-cte-inline-view"),
        NONNULL_EMAIL_CTE_INLINE_VIEW("nonnull-email-cte-inline-view"),
        NULL_PLAN_CODE_CTE_INLINE_VIEW("null-plan-code-cte-inline-view"),
        ACTIVE_ROWS_FIELD_CLASS_SQLTYPE("active-rows-field-class-sqltype"),
        PLANLESS_LOGIN_COUNT_FIELD_CLASS_SQLTYPE("planless-login-count-field-class-sqltype"),
        ACTIVE_NONNULL_EMAIL_TWO_HOP_CTE_INLINE_VIEW("active-nonnull-email-two-hop-cte-inline-view"),
        ACTIVE_NONNULL_EMAIL_TWO_HOP_FIELD_CLASS_SQLTYPE("active-nonnull-email-two-hop-field-class-sqltype");

        private final String id;

        Family(String id) {
            this.id = id;
        }

        String id() {
            return id;
        }
    }

    record CompositionEquivalenceCase(
            String profileId,
            Family family,
            SelectCaseModel.SelectCase referenceCase,
            String namedCteJavaSource,
            String inlineViewJavaSource
    ) {
        CompositionEquivalenceCase {
            if (profileId == null || profileId.isBlank()) {
                throw new IllegalArgumentException("profileId must not be blank");
            }
            Objects.requireNonNull(family, "family");
            Objects.requireNonNull(referenceCase, "referenceCase");
            Objects.requireNonNull(namedCteJavaSource, "namedCteJavaSource");
            Objects.requireNonNull(inlineViewJavaSource, "inlineViewJavaSource");
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

    static CompositionEquivalenceCase of(
            String profileId,
            Family family,
            SelectCaseModel.SelectCase referenceCase,
            String namedCteJavaSource,
            String inlineViewJavaSource
    ) {
        return new CompositionEquivalenceCase(profileId, family, referenceCase, namedCteJavaSource, inlineViewJavaSource);
    }
}
