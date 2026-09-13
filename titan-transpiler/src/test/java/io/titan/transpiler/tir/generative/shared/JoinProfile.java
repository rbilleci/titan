package io.titan.transpiler.tir.generative.shared;

public enum JoinProfile {
    INNER_JOIN_BASIC("transpiler-diff-joins");

    private final String id;

    JoinProfile(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    public static JoinProfile fromId(String raw) {
        for (JoinProfile profile : values()) {
            if (profile.id.equalsIgnoreCase(raw) || profile.name().equalsIgnoreCase(raw)) {
                return profile;
            }
        }
        throw new IllegalArgumentException("Unknown join profile: " + raw);
    }
}
