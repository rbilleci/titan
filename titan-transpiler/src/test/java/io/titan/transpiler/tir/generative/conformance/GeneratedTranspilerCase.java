package io.titan.transpiler.tir.generative.conformance;

import java.util.List;

record GeneratedTranspilerCase(
        long seed,
        String profile,
        String family,
        String className,
        String source,
        String summary,
        boolean shouldFail,
        List<String> expectedFragments
) {
}
