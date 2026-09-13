package io.titan.transpiler.tir.generative.differential;

public enum SelectDifferentialProfile {
    BASIC_SELECT("transpiler-diff-basic-select");

    private final String id;

    SelectDifferentialProfile(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    public static SelectDifferentialProfile fromId(String raw) {
        for (SelectDifferentialProfile profile : values()) {
            if (profile.id.equalsIgnoreCase(raw) || profile.name().equalsIgnoreCase(raw)) {
                return profile;
            }
        }
        throw new IllegalArgumentException("Unknown select differential profile: " + raw);
    }
}
