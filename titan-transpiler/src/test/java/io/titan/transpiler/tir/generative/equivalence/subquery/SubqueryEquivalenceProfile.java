package io.titan.transpiler.tir.generative.equivalence.subquery;

enum SubqueryEquivalenceProfile {
    SUBQUERY_FORMS("transpiler-diff-metamorphic-subqueries");

    private final String id;

    SubqueryEquivalenceProfile(String id) {
        this.id = id;
    }

    String id() {
        return id;
    }

    static SubqueryEquivalenceProfile fromId(String raw) {
        for (SubqueryEquivalenceProfile profile : values()) {
            if (profile.id.equalsIgnoreCase(raw) || profile.name().equalsIgnoreCase(raw)) {
                return profile;
            }
        }
        throw new IllegalArgumentException("Unknown Phase C subquery-equivalence profile: " + raw);
    }
}
