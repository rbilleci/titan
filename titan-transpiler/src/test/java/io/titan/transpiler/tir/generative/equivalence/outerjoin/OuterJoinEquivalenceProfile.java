package io.titan.transpiler.tir.generative.equivalence.outerjoin;

enum OuterJoinEquivalenceProfile {
    OUTER_JOIN_FORMS("transpiler-diff-metamorphic-outer-joins");

    private final String id;

    OuterJoinEquivalenceProfile(String id) {
        this.id = id;
    }

    String id() {
        return id;
    }

    static OuterJoinEquivalenceProfile fromId(String raw) {
        for (OuterJoinEquivalenceProfile profile : values()) {
            if (profile.id.equalsIgnoreCase(raw) || profile.name().equalsIgnoreCase(raw)) {
                return profile;
            }
        }
        throw new IllegalArgumentException("Unknown Phase C outer-join-equivalence profile: " + raw);
    }
}
