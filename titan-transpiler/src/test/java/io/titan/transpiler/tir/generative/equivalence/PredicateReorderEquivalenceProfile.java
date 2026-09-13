package io.titan.transpiler.tir.generative.equivalence;

enum PredicateReorderEquivalenceProfile {
    PREDICATE_REORDER("transpiler-diff-metamorphic");

    private final String id;

    PredicateReorderEquivalenceProfile(String id) {
        this.id = id;
    }

    String id() {
        return id;
    }

    static PredicateReorderEquivalenceProfile fromId(String raw) {
        for (PredicateReorderEquivalenceProfile profile : values()) {
            if (profile.id.equalsIgnoreCase(raw) || profile.name().equalsIgnoreCase(raw)) {
                return profile;
            }
        }
        throw new IllegalArgumentException("Unknown predicate-reorder equivalence profile: " + raw);
    }
}
