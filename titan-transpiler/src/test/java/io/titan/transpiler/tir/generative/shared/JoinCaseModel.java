package io.titan.transpiler.tir.generative.shared;

import java.util.Objects;

public final class JoinCaseModel {

    private JoinCaseModel() {
    }

    public enum Family {
        INNER_JOIN_ACTIVE_ACCOUNTS("inner-join-active-accounts"),
        INNER_JOIN_PAID_PLANS("inner-join-paid-plans"),
        INNER_JOIN_DUPLICATE_PLAN_MATCHES("inner-join-duplicate-plan-matches"),
        LEFT_JOIN_ACTIVE_NULL_EXTENSION("left-join-active-null-extension"),
        LEFT_JOIN_PAID_FILTER_PRESERVES_NULLS("left-join-paid-filter-preserves-nulls"),
        LEFT_JOIN_PAID_WHERE_COLLAPSES_NULL_EXTENSION("left-join-paid-where-collapses-null-extension"),
        TWO_JOIN_ACTIVE_PLAN_FAMILY("two-join-active-plan-family"),
        TWO_JOIN_DUPLICATE_SECOND_HOP_MULTIPLICATION("two-join-duplicate-second-hop-multiplication"),
        TWO_JOIN_SECOND_HOP_FILTER("two-join-second-hop-filter"),
        LEFT_JOIN_DUPLICATE_NULL_EXTENSION("left-join-duplicate-null-extension"),
        LEFT_TWO_JOIN_NULL_EXTENSION("left-two-join-null-extension"),
        LEFT_TWO_JOIN_SECOND_HOP_FILTER("left-two-join-second-hop-filter"),
        LEFT_TWO_JOIN_SECOND_HOP_WHERE_COLLAPSES_NULL_EXTENSION("left-two-join-second-hop-where-collapses-null-extension");

        private final String id;

        Family(String id) {
            this.id = id;
        }

        public String id() {
            return id;
        }
    }

    public record JoinCase(String profileId, Family family) {
        public JoinCase {
            if (profileId == null || profileId.isBlank()) {
                throw new IllegalArgumentException("profileId must not be blank");
            }
            Objects.requireNonNull(family, "family");
        }

        public String toStableJson() {
            return """
                    {
                      "profileId": "%s",
                      "family": "%s"
                    }
                    """.formatted(profileId, family.id());
        }
    }

    public static JoinCase of(String profileId, Family family) {
        return new JoinCase(profileId, family);
    }
}
