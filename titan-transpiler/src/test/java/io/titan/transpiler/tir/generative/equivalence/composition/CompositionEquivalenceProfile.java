package io.titan.transpiler.tir.generative.equivalence.composition;

enum CompositionEquivalenceProfile {
    CTE_INLINE_VIEW("transpiler-diff-metamorphic-composition");

    private final String id;

    CompositionEquivalenceProfile(String id) {
        this.id = id;
    }

    String id() {
        return id;
    }

    static CompositionEquivalenceProfile fromId(String raw) {
        for (CompositionEquivalenceProfile profile : values()) {
            if (profile.id.equalsIgnoreCase(raw) || profile.name().equalsIgnoreCase(raw)) {
                return profile;
            }
        }
        throw new IllegalArgumentException("Unknown Phase C composition-equivalence profile: " + raw);
    }
}
