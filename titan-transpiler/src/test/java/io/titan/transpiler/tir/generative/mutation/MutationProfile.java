package io.titan.transpiler.tir.generative.mutation;

enum MutationProfile {
    BASIC("transpiler-mutation-basic"),
    JOINS("transpiler-mutation-joins"),
    SUBQUERIES("transpiler-mutation-subqueries");

    private final String id;

    MutationProfile(String id) {
        this.id = id;
    }

    String id() {
        return id;
    }

    static MutationProfile fromId(String raw) {
        for (MutationProfile profile : values()) {
            if (profile.id.equalsIgnoreCase(raw) || profile.name().equalsIgnoreCase(raw)) {
                return profile;
            }
        }
        throw new IllegalArgumentException("Unknown mutation profile: " + raw);
    }
}
