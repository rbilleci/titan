package io.titan.transpiler.tir.generative.equivalence;

enum FormEquivalenceProfile {
    HELPER_EXPLICIT("transpiler-diff-metamorphic-forms");

    private final String id;

    FormEquivalenceProfile(String id) {
        this.id = id;
    }

    String id() {
        return id;
    }

    static FormEquivalenceProfile fromId(String raw) {
        for (FormEquivalenceProfile profile : values()) {
            if (profile.id.equalsIgnoreCase(raw) || profile.name().equalsIgnoreCase(raw)) {
                return profile;
            }
        }
        throw new IllegalArgumentException("Unknown Phase C form-equivalence profile: " + raw);
    }
}
