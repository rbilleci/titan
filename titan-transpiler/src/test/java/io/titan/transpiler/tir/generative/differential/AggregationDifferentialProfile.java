package io.titan.transpiler.tir.generative.differential;

enum AggregationDifferentialProfile {
    AGGREGATION_BASIC("transpiler-diff-aggregation");

    private final String id;

    AggregationDifferentialProfile(String id) {
        this.id = id;
    }

    String id() {
        return id;
    }

    static AggregationDifferentialProfile fromId(String raw) {
        for (AggregationDifferentialProfile profile : values()) {
            if (profile.id.equalsIgnoreCase(raw) || profile.name().equalsIgnoreCase(raw)) {
                return profile;
            }
        }
        throw new IllegalArgumentException("Unknown Phase B aggregation profile: " + raw);
    }
}
