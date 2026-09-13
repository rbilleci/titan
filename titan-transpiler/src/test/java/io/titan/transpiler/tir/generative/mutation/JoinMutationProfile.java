package io.titan.transpiler.tir.generative.mutation;

enum JoinMutationProfile {
    JOIN_FORMS("transpiler-mutation-joins");

    private final String id;

    JoinMutationProfile(String id) {
        this.id = id;
    }

    String id() {
        return id;
    }

    static JoinMutationProfile fromId(String raw) {
        for (JoinMutationProfile profile : values()) {
            if (profile.id.equalsIgnoreCase(raw) || profile.name().equalsIgnoreCase(raw)) {
                return profile;
            }
        }
        throw new IllegalArgumentException("Unknown Phase D join-mutation profile: " + raw);
    }
}
