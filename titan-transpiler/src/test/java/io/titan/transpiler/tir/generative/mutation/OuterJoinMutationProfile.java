package io.titan.transpiler.tir.generative.mutation;

enum OuterJoinMutationProfile {
    OUTER_JOIN_FORMS("transpiler-mutation-outer-joins");

    private final String id;

    OuterJoinMutationProfile(String id) {
        this.id = id;
    }

    String id() {
        return id;
    }

    static OuterJoinMutationProfile fromId(String raw) {
        for (OuterJoinMutationProfile profile : values()) {
            if (profile.id.equalsIgnoreCase(raw) || profile.name().equalsIgnoreCase(raw)) {
                return profile;
            }
        }
        throw new IllegalArgumentException("Unknown Phase D outer-join mutation profile: " + raw);
    }
}
