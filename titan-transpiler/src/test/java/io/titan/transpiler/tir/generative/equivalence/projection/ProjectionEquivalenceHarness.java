package io.titan.transpiler.tir.generative.equivalence.projection;

import io.titan.transpiler.tir.generative.shared.SelectPostgresSqlHarness;
import io.titan.transpiler.tir.generative.shared.ReferenceResultComparison;
import io.titan.transpiler.tir.generative.shared.ReferenceResultModels.ResultRow;
import io.titan.transpiler.tir.generative.shared.ReferenceResultModels.ReferenceResult;
import io.titan.transpiler.tir.generative.shared.FixtureCatalog;
import io.titan.transpiler.tir.generative.shared.FailureTriageSupport;
import io.titan.transpiler.tir.generative.shared.ArtifactSummaryWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.testcontainers.containers.PostgreSQLContainer;

final class ProjectionEquivalenceHarness {

    private static final PostgreSQLContainer<?> POSTGRES = io.titan.test.TestContainers.postgres();


    record Run(
            ProjectionEquivalenceCaseModel.ProjectionEquivalenceCase projectionCase,
            SelectPostgresSqlHarness.SelectRun originalRun,
            SelectPostgresSqlHarness.SelectRun reorderedRun,
            String mismatchSummary,
            Path artifactDir
    ) {
    }

    Run run(ProjectionEquivalenceProfile profile, long seed, Path tempDir) throws Exception {
        ProjectionEquivalenceCaseModel.ProjectionEquivalenceCase projectionCase = switch (profile) {
            case PROJECTION_REORDER -> new ProjectionEquivalenceGenerator().generate(seed);
        };
        Path artifactRoot = Files.createDirectories(tempDir.resolve(profile.id() + "-seed-" + Long.toUnsignedString(seed)));
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(),
                POSTGRES.getPassword())) {
            SelectPostgresSqlHarness harness = new SelectPostgresSqlHarness();
            var fixture = FixtureCatalog.accountsFixture();
            var originalRun = harness.runSelectCase(connection, projectionCase.originalCase(), fixture, artifactRoot.resolve("original"));
            var reorderedRun = harness.runSelectCase(connection, projectionCase.reorderedCase(), fixture, artifactRoot.resolve("reordered"));
            String mismatch = compare(originalRun, reorderedRun);
            Files.writeString(artifactRoot.resolve("projection-case.json"), projectionCase.toStableJson());
            Files.writeString(artifactRoot.resolve("repro.txt"), """
                    Replay this Phase C projection equivalence case with:

                    ./gradlew :titan-transpiler:test \
                      --tests io.titan.transpiler.tir.generative.equivalence.projection.ProjectionEquivalenceReplayTest \
                      -Dtitan.phasec.profile=%s \
                      -Dtitan.phasec.seed=%s \
                      --no-daemon
                    """.formatted(profile.id(), Long.toUnsignedString(seed)));
            if (mismatch != null) {
                Files.writeString(artifactRoot.resolve("mismatch.txt"), mismatch + System.lineSeparator());
            }
            FailureTriageSupport.Triage triage = FailureTriageSupport.forMismatch(mismatch);
            ArtifactSummaryWriter.write(
                    artifactRoot,
                    "Phase C projection equivalence artifact summary",
                    mismatch == null ? "green" : "mismatch",
                    Map.of(
                            "Phase", "C",
                            "Profile", profile.id(),
                            "Failure bucket", triage.bucket(),
                            "Likely layer hint", triage.likelyLayerHint(),
                            "Family", projectionCase.family().id()),
                    mismatch == null
                            ? List.of("projection-case.json", "repro.txt")
                            : List.of("projection-case.json", "mismatch.txt", "repro.txt"));
            return new Run(projectionCase, originalRun, reorderedRun, mismatch, artifactRoot);
        }
    }

    private static String compare(
            SelectPostgresSqlHarness.SelectRun originalRun,
            SelectPostgresSqlHarness.SelectRun reorderedRun
    ) {
        if (originalRun.mismatchSummary() != null) {
            return "Original case mismatch vs reference: " + originalRun.mismatchSummary();
        }
        if (reorderedRun.mismatchSummary() != null) {
            return "Reordered case mismatch vs reference: " + reorderedRun.mismatchSummary();
        }
        var normalizedOriginalExpected = normalize(originalRun.expected());
        var normalizedReorderedExpected = normalize(reorderedRun.expected());
        String expectedMismatch = new ReferenceResultComparison().summarizeMismatch(normalizedOriginalExpected, normalizedReorderedExpected);
        if (expectedMismatch != null) {
            return "Reference projection-equivalence mismatch: " + expectedMismatch;
        }
        var normalizedOriginalActual = normalize(originalRun.actual());
        var normalizedReorderedActual = normalize(reorderedRun.actual());
        String actualMismatch = new ReferenceResultComparison().summarizeMismatch(normalizedOriginalActual, normalizedReorderedActual);
        if (actualMismatch != null) {
            return "SQL projection-equivalence mismatch: " + actualMismatch;
        }
        return null;
    }

    private static ReferenceResult normalize(
            ReferenceResult result
    ) {
        List<ResultRow> normalized = result.rows().stream()
                .map(row -> {
                    Map<String, Object> sorted = row.values().entrySet().stream()
                            .sorted(Map.Entry.comparingByKey())
                            .collect(LinkedHashMap::new, (m, e) -> m.put(e.getKey(), e.getValue()), Map::putAll);
                    return new ResultRow(sorted);
                })
                .sorted(Comparator.comparing(ResultRow::toStableJson))
                .toList();
        return new ReferenceResult(normalized);
    }
}
