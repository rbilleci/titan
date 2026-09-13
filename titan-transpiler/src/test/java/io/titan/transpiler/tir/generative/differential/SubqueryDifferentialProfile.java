package io.titan.transpiler.tir.generative.differential;

enum SubqueryDifferentialProfile {
    SUBQUERY_BASIC("transpiler-diff-subquery-cte");

    private final String id;

    SubqueryDifferentialProfile(String id) {
        this.id = id;
    }

    String id() {
        return id;
    }

    static SubqueryDifferentialProfile fromId(String raw) {
        for (SubqueryDifferentialProfile profile : values()) {
            if (profile.id.equalsIgnoreCase(raw) || profile.name().equalsIgnoreCase(raw)) {
                return profile;
            }
        }
        throw new IllegalArgumentException("Unknown Phase B subquery profile: " + raw);
    }
}
