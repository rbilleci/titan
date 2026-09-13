package io.titan.transpiler.tir.generative.mutation;

import io.titan.transpiler.tir.generative.shared.SelectPostgresSqlHarness;
import io.titan.transpiler.tir.generative.shared.FailureTriageSupport;
import io.titan.transpiler.tir.generative.shared.ArtifactSummaryWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

final class BasicMutationArtifactWriter {

    void write(
            Path artifactRoot,
            MutationCaseModel.MutationCase mutationCase,
            SelectPostgresSqlHarness.SelectRun selectRun
    ) throws IOException {
        Files.writeString(artifactRoot.resolve("phase-d-mutation-case.json"), mutationCase.toStableJson() + System.lineSeparator());
        Files.writeString(artifactRoot.resolve("phase-d-base-case.json"), mutationCase.baseCase().toStableJson() + System.lineSeparator());
        Files.writeString(artifactRoot.resolve("phase-d-mutated-case.json"), mutationCase.mutatedCase().toStableJson() + System.lineSeparator());
        Files.writeString(artifactRoot.resolve("phase-d-repro.txt"), """
                Replay this Phase D mutation case with:

                  ./gradlew :titan-transpiler:test \
                    --tests io.titan.transpiler.tir.generative.mutation.BasicMutationReplayTest \
                    -Dtitan.phased.profile=%s \
                    -Dtitan.phased.seed=%s \
                    -Dtitan.phased.mutator=%s \
                    --no-daemon
                """.formatted(
                mutationCase.profileId(),
                Long.toUnsignedString(mutationCase.baseSeed()),
                mutationCase.mutatorId().id()));
        if (selectRun.mismatchSummary() != null) {
            Files.writeString(
                    artifactRoot.resolve("phase-d-mismatch-summary.txt"),
                    selectRun.mismatchSummary() + System.lineSeparator());
        }
        if (!mutationCase.reductionSteps().isEmpty()) {
            Files.writeString(
                    artifactRoot.resolve("phase-d-reduced-case.json"),
                    mutationCase.mutatedCase().toStableJson() + System.lineSeparator());
            Files.writeString(
                    artifactRoot.resolve("phase-d-reduction-trace.json"),
                    reductionTraceJson(mutationCase.reductionSteps()) + System.lineSeparator());
        }
        FailureTriageSupport.Triage triage = FailureTriageSupport.forMutationClassification(mutationCase.classification());
        ArtifactSummaryWriter.write(
                artifactRoot,
                "Phase D mutation artifact summary",
                selectRun.mismatchSummary() == null ? "green" : "mismatch",
                java.util.Map.of(
                        "Phase", "D",
                        "Profile", mutationCase.profileId(),
                        "Failure bucket", triage.bucket(),
                        "Likely layer hint", triage.likelyLayerHint(),
                        "Base seed", Long.toUnsignedString(mutationCase.baseSeed()),
                        "Mutator", mutationCase.mutatorId().id(),
                        "Classification", mutationCase.classification().name()),
                selectRun.mismatchSummary() == null
                        ? java.util.List.of("phase-d-mutation-case.json", "phase-d-base-case.json", "phase-d-mutated-case.json", "phase-d-repro.txt")
                        : java.util.List.of("phase-d-mutation-case.json", "phase-d-base-case.json", "phase-d-mutated-case.json", "phase-d-mismatch-summary.txt", "phase-d-repro.txt"));
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
