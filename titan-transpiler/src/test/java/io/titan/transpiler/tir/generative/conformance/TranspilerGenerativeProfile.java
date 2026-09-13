package io.titan.transpiler.tir.generative.conformance;

enum TranspilerGenerativeProfile {
    BASIC_SELECT("transpiler-basic-select"),
    INVALID("transpiler-invalid"),
    SUBQUERY_CTE("transpiler-subquery-cte"),
    AGGREGATION("transpiler-aggregation"),
    JOIN_COMPOSITION("transpiler-join-composition"),
    INVALID_COMPOSITION("transpiler-invalid-composition");

    private final String id;

    TranspilerGenerativeProfile(String id) {
        this.id = id;
    }

    String id() {
        return id;
    }

    static TranspilerGenerativeProfile fromId(String raw) {
        for (TranspilerGenerativeProfile profile : values()) {
            if (profile.id.equalsIgnoreCase(raw) || profile.name().equalsIgnoreCase(raw)) {
                return profile;
            }
        }
        throw new IllegalArgumentException("Unknown generative profile: " + raw);
    }
}
