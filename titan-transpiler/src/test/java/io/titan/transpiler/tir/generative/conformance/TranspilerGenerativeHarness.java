package io.titan.transpiler.tir.generative.conformance;

import io.titan.transpiler.tir.generative.shared.ArtifactSummaryWriter;
import io.titan.transpiler.tir.TranspilationPipeline;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

final class TranspilerGenerativeHarness {

    record HarnessResult(
            GeneratedTranspilerCase generatedCase,
            List<TranspilationPipeline.GeneratedSql> generatedSql,
            String errorMessage,
            Path artifactDir
    ) {
    }

    HarnessResult run(TranspilerGenerativeProfile profile, long seed, Path tempDir) throws Exception {
        return switch (profile) {
            case BASIC_SELECT -> runBasicSelect(seed, tempDir);
            case INVALID -> runInvalidCase(seed, tempDir);
            case SUBQUERY_CTE -> runSubqueryCte(seed, tempDir);
            case AGGREGATION -> runAggregation(seed, tempDir);
            case JOIN_COMPOSITION -> runJoinComposition(seed, tempDir);
            case INVALID_COMPOSITION -> runInvalidComposition(seed, tempDir);
        };
    }

    HarnessResult runBasicSelect(long seed, Path tempDir) throws Exception {
        GeneratedTranspilerCase generatedCase = new TranspilerBasicSelectGenerator().generate(seed);
        return execute(generatedCase, tempDir);
    }

    HarnessResult runInvalidCase(long seed, Path tempDir) throws Exception {
        GeneratedTranspilerCase generatedCase = new TranspilerInvalidCaseGenerator().generate(seed);
        return execute(generatedCase, tempDir);
    }

    HarnessResult runSubqueryCte(long seed, Path tempDir) throws Exception {
        GeneratedTranspilerCase generatedCase = new TranspilerSubqueryCteGenerator().generate(seed);
        return execute(generatedCase, tempDir);
    }

    HarnessResult runAggregation(long seed, Path tempDir) throws Exception {
        GeneratedTranspilerCase generatedCase = new TranspilerAggregationGenerator().generate(seed);
        return execute(generatedCase, tempDir);
    }

    HarnessResult runJoinComposition(long seed, Path tempDir) throws Exception {
        GeneratedTranspilerCase generatedCase = new TranspilerJoinCompositionGenerator().generate(seed);
        return execute(generatedCase, tempDir);
    }

    HarnessResult runInvalidComposition(long seed, Path tempDir) throws Exception {
        GeneratedTranspilerCase generatedCase = new TranspilerInvalidCompositionGenerator().generate(seed);
        return execute(generatedCase, tempDir);
    }

    private HarnessResult execute(GeneratedTranspilerCase generatedCase, Path tempDir) throws Exception {
        Path artifactDir = Files.createDirectories(tempDir.resolve(generatedCase.profile() + "-seed-" + Long.toUnsignedString(generatedCase.seed())));
        Path sourceFile = artifactDir.resolve(generatedCase.className() + ".java");
        Files.writeString(sourceFile, generatedCase.source());
        writeMetadata(artifactDir, generatedCase);
        writeJsonMetadata(artifactDir, generatedCase);
        writeReplayInstructions(artifactDir, generatedCase);

        try {
            List<TranspilationPipeline.GeneratedSql> generatedSql = new TranspilationPipeline().transpile(
                    List.of(sourceFile),
                    List.of(),
                    List.of("postgresql", "mysql"),
                    List.of("app"),
                    true);

            writeSqlArtifacts(artifactDir, generatedSql);
            return new HarnessResult(generatedCase, generatedSql, null, artifactDir);
        } catch (IllegalArgumentException error) {
            writeErrorArtifact(artifactDir, error);
            return new HarnessResult(generatedCase, List.of(), error.getMessage(), artifactDir);
        }
    }

    private static void writeMetadata(Path artifactDir, GeneratedTranspilerCase generatedCase) throws IOException {
        Files.writeString(artifactDir.resolve("case.txt"), """
                seed: %s
                profile: %s
                family: %s
                class: %s
                summary: %s
                shouldFail: %s
                expectedFragments: %s
                """.formatted(
                Long.toUnsignedString(generatedCase.seed()),
                generatedCase.profile(),
                generatedCase.family(),
                generatedCase.className(),
                generatedCase.summary(),
                generatedCase.shouldFail(),
                generatedCase.expectedFragments()
        ));
    }

    private static void writeJsonMetadata(Path artifactDir, GeneratedTranspilerCase generatedCase) throws IOException {
        Files.writeString(artifactDir.resolve("case.json"), """
                {
                  "seed": "%s",
                  "profile": "%s",
                  "family": "%s",
                  "className": "%s",
                  "summary": "%s",
                  "shouldFail": %s,
                  "expectedFragments": %s
                }
                """.formatted(
                Long.toUnsignedString(generatedCase.seed()),
                jsonEscape(generatedCase.profile()),
                jsonEscape(generatedCase.family()),
                jsonEscape(generatedCase.className()),
                jsonEscape(generatedCase.summary()),
                generatedCase.shouldFail(),
                jsonStringArray(generatedCase.expectedFragments())
        ));
    }

    private static void writeReplayInstructions(Path artifactDir, GeneratedTranspilerCase generatedCase) throws IOException {
        Files.writeString(artifactDir.resolve("repro.txt"), """
                Replay this generated case with:

                source ~/.sdkman/bin/sdkman-init.sh && ./gradlew :titan-transpiler:test \
                  --tests io.titan.transpiler.tir.generative.conformance.TranspilerGenerativeReplayTest \
                  -Dtitan.generative.profile=%s \
                  -Dtitan.generative.seed=%s \
                  --no-daemon
                """.formatted(
                generatedCase.profile(),
                Long.toUnsignedString(generatedCase.seed())
        ));
        ArtifactSummaryWriter.write(
                artifactDir,
                "Phase A generative artifact summary",
                generatedCase.shouldFail() ? "expected-rejection" : "generated",
                java.util.Map.of(
                        "Phase", "A",
                        "Profile", generatedCase.profile(),
                        "Family", generatedCase.family(),
                        "Expected failure", Boolean.toString(generatedCase.shouldFail())),
                java.util.List.of("case.json", "repro.txt"));
    }

    private static void writeSqlArtifacts(Path artifactDir, List<TranspilationPipeline.GeneratedSql> generatedSql) throws IOException {
        for (TranspilationPipeline.GeneratedSql sql : generatedSql) {
            String fileName = sql.target() + "-" + sql.methodName() + ".sql";
            Files.writeString(artifactDir.resolve(fileName), sql.sql());
        }
    }

    private static void writeErrorArtifact(Path artifactDir, IllegalArgumentException error) throws IOException {
        Files.writeString(artifactDir.resolve("error.txt"), error.getMessage() == null ? "<null>" : error.getMessage());
    }

    private static String jsonStringArray(List<String> values) {
        return values.stream()
                .map(TranspilerGenerativeHarness::jsonEscape)
                .map(value -> "\"" + value + "\"")
                .collect(java.util.stream.Collectors.joining(", ", "[", "]"));
    }

    private static String jsonEscape(String raw) {
        return raw
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n");
    }
}
