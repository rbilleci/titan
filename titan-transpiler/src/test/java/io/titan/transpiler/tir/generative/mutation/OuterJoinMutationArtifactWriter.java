package io.titan.transpiler.tir.generative.mutation;

import io.titan.transpiler.tir.generative.shared.ReferenceResultModels.ReferenceResult;
import io.titan.transpiler.tir.generative.shared.FailureTriageSupport;
import io.titan.transpiler.tir.generative.shared.ArtifactSummaryWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

final class OuterJoinMutationArtifactWriter {

    void write(
            Path artifactRoot,
            OuterJoinMutationCaseModel.OuterJoinMutationCase mutationCase,
            ReferenceResult expected,
            ReferenceResult actual,
            String mismatchSummary
    ) throws IOException {
        Files.writeString(artifactRoot.resolve("outer-join-mutation-case.json"), mutationCase.toStableJson() + System.lineSeparator());
        Files.writeString(artifactRoot.resolve("outer-join-base-case.java"), mutationCase.baseJavaSource() + System.lineSeparator());
        Files.writeString(artifactRoot.resolve("outer-join-mutated-case.java"), mutationCase.mutatedJavaSource() + System.lineSeparator());
        Files.writeString(artifactRoot.resolve("outer-join-reference-result.json"), expected.toStableJson() + System.lineSeparator());
        Files.writeString(artifactRoot.resolve("outer-join-sql-result.json"), actual.toStableJson() + System.lineSeparator());
        Files.writeString(artifactRoot.resolve("outer-join-repro.txt"), """
                Replay this Phase D outer-join mutation case with:

                  ./gradlew :titan-transpiler:test \
                    --tests io.titan.transpiler.tir.generative.mutation.OuterJoinMutationReplayTest \
                    -Dtitan.phased.profile=%s \
                    -Dtitan.phased.seed=<seed> \
                    --no-daemon
                """.formatted(mutationCase.profileId()));
        if (mismatchSummary != null) {
            Files.writeString(artifactRoot.resolve("outer-join-mismatch.txt"), mismatchSummary + System.lineSeparator());
        }
        if (!mutationCase.reductionSteps().isEmpty()) {
            Files.writeString(artifactRoot.resolve("outer-join-reduced-case.java"), mutationCase.mutatedJavaSource() + System.lineSeparator());
            Files.writeString(
                    artifactRoot.resolve("outer-join-reduction-trace.json"),
                    reductionTraceJson(mutationCase.reductionSteps()) + System.lineSeparator());
        }
        FailureTriageSupport.Triage triage = FailureTriageSupport.forMutationClassification(mutationCase.classification());
        ArtifactSummaryWriter.write(
                artifactRoot,
                "Phase D outer-join mutation artifact summary",
                mismatchSummary == null ? "green" : "mismatch",
                java.util.Map.of(
                        "Phase", "D",
                        "Profile", mutationCase.profileId(),
                        "Failure bucket", triage.bucket(),
                        "Likely layer hint", triage.likelyLayerHint(),
                        "Family", mutationCase.family().id(),
                        "Base family", mutationCase.baseCase().family().id(),
                        "Classification", mutationCase.classification().name()),
                mismatchSummary == null
                        ? java.util.List.of("outer-join-mutation-case.json", "outer-join-base-case.java", "outer-join-mutated-case.java", "outer-join-repro.txt")
                        : java.util.List.of("outer-join-mutation-case.json", "outer-join-base-case.java", "outer-join-mutated-case.java", "outer-join-mismatch.txt", "outer-join-repro.txt"));
    }

    private static String reductionTraceJson(List<MutationCaseModel.ReductionStep> steps) {
        StringBuilder json = new StringBuilder("[\n");
        for (int i = 0; i < steps.size(); i++) {
            json.append(steps.get(i).toStableJson());
            if (i + 1 < steps.size()) {
                json.append(",\n");
            } else {
                json.append('\n');
            }
        }
        json.append(']');
        return json.toString();
    }
}
