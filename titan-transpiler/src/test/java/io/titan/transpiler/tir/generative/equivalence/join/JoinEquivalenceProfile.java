package io.titan.transpiler.tir.generative.equivalence.join;

enum JoinEquivalenceProfile {
    JOIN_FORMS("transpiler-diff-metamorphic-joins");

    private final String id;

    JoinEquivalenceProfile(String id) {
        this.id = id;
    }

    String id() {
        return id;
    }

    static JoinEquivalenceProfile fromId(String raw) {
        for (JoinEquivalenceProfile profile : values()) {
            if (profile.id.equalsIgnoreCase(raw) || profile.name().equalsIgnoreCase(raw)) {
                return profile;
            }
        }
        throw new IllegalArgumentException("Unknown Phase C join-equivalence profile: " + raw);
    }
}
