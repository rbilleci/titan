package io.titan.transpiler.tir;

public final class StandardTirTypes {
    private StandardTirTypes() {
    }

    public static TirType text() {
        return new TTextType();
    }
}
