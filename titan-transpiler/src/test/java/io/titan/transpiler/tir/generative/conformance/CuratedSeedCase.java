package io.titan.transpiler.tir.generative.conformance;

record CuratedSeedCase(
        TranspilerGenerativeProfile profile,
        long seed,
        String rationale
) {
}
