package io.titan.transpiler.tir.generative.differential;

import java.util.Objects;

final class SubqueryDifferentialCaseModel {

    enum Family {
        EXISTS_ENTERPRISE("exists-enterprise"),
        NOT_EXISTS_VIP_ACTIVE("not-exists-vip-active"),
        CORRELATED_EXISTS_ACTIVE_PLAN("correlated-exists-active-plan"),
        CORRELATED_NOT_EXISTS_NULL_PLAN("correlated-not-exists-null-plan"),
        CORRELATED_SCALAR_EMAIL_BY_PLAN("correlated-scalar-email-by-plan"),
        CORRELATED_SCALAR_NULL_OR_ABSENT_EMAIL_BY_PLAN("correlated-scalar-null-or-absent-email-by-plan"),
        SCALAR_EMAIL_LOOKUP("scalar-email-lookup"),
        SCALAR_NULL_EMAIL_LOOKUP("scalar-null-email-lookup"),
        CTE_ACTIVE_ROWS("cte-active-rows");

        private final String id;

        Family(String id) {
            this.id = id;
        }

        String id() {
            return id;
        }
    }

    record Case(String profileId, Family family) {
        Case {
            if (profileId == null || profileId.isBlank()) {
                throw new IllegalArgumentException("profileId must not be blank");
            }
            Objects.requireNonNull(family, "family");
        }

        String toStableJson() {
            return """
                    {
                      "profileId": "%s",
                      "family": "%s"
                    }
                    """.formatted(profileId, family.id());
        }
    }

    static Case of(String profileId, Family family) {
        return new Case(profileId, family);
    }
}
