package io.titan.transpiler.tir.generative.equivalence.subquery;

import io.titan.transpiler.tir.generative.shared.SelectCaseModel;
import java.util.Objects;

final class SubqueryEquivalenceCaseModel {

    enum Family {
        CORRELATED_EXISTS_PLANCODE_NONNULL("correlated-exists-plancode-nonnull"),
        CORRELATED_NOT_EXISTS_PLANCODE_NULL("correlated-not-exists-plancode-null"),
        CORRELATED_SCALAR_SELF_EMAIL_NONNULL("correlated-scalar-self-email-nonnull"),
        CORRELATED_EXISTS_SELF_ID_EMAIL_NONNULL_JOIN("correlated-exists-self-id-email-nonnull-join");

        private final String id;

        Family(String id) {
            this.id = id;
        }

        String id() {
            return id;
        }
    }

    record SubqueryEquivalenceCase(
            String profileId,
            Family family,
            SelectCaseModel.SelectCase referenceCase,
            String subqueryJavaSource,
            String rewrittenJavaSource
    ) {
        SubqueryEquivalenceCase {
            if (profileId == null || profileId.isBlank()) {
                throw new IllegalArgumentException("profileId must not be blank");
            }
            Objects.requireNonNull(family, "family");
            Objects.requireNonNull(referenceCase, "referenceCase");
            Objects.requireNonNull(subqueryJavaSource, "subqueryJavaSource");
            Objects.requireNonNull(rewrittenJavaSource, "rewrittenJavaSource");
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

    static SubqueryEquivalenceCase of(
            String profileId,
            Family family,
            SelectCaseModel.SelectCase referenceCase,
            String subqueryJavaSource,
            String rewrittenJavaSource
    ) {
        return new SubqueryEquivalenceCase(profileId, family, referenceCase, subqueryJavaSource, rewrittenJavaSource);
    }
}
