package io.titan.transpiler.tir.generative.mutation;

enum TwoHopJoinMutationProfile {
    TWO_HOP_JOIN_FORMS("transpiler-mutation-two-hop-joins");

    private final String id;

    TwoHopJoinMutationProfile(String id) {
        this.id = id;
    }

    String id() {
        return id;
    }

    static TwoHopJoinMutationProfile fromId(String raw) {
        for (TwoHopJoinMutationProfile profile : values()) {
            if (profile.id.equalsIgnoreCase(raw) || profile.name().equalsIgnoreCase(raw)) {
                return profile;
            }
        }
        throw new IllegalArgumentException("Unknown Phase D two-hop join mutation profile: " + raw);
    }
}
