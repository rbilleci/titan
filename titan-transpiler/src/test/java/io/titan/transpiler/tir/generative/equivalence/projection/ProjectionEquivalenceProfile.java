package io.titan.transpiler.tir.generative.equivalence.projection;

enum ProjectionEquivalenceProfile {
    PROJECTION_REORDER("transpiler-diff-metamorphic-projection");

    private final String id;

    ProjectionEquivalenceProfile(String id) {
        this.id = id;
    }

    String id() {
        return id;
    }

    static ProjectionEquivalenceProfile fromId(String raw) {
        for (ProjectionEquivalenceProfile profile : values()) {
            if (profile.id.equalsIgnoreCase(raw) || profile.name().equalsIgnoreCase(raw)) {
                return profile;
            }
        }
        throw new IllegalArgumentException("Unknown Phase C projection-equivalence profile: " + raw);
    }
}
