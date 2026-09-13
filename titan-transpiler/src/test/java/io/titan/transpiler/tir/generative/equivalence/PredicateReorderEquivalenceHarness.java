package io.titan.transpiler.tir.generative.equivalence;

import io.titan.transpiler.tir.generative.shared.SelectPostgresSqlHarness;
import io.titan.transpiler.tir.generative.shared.ReferenceResultComparison;
import io.titan.transpiler.tir.generative.shared.FixtureCatalog;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Objects;
import org.testcontainers.containers.PostgreSQLContainer;

final class PredicateReorderEquivalenceHarness {

    private static final PostgreSQLContainer<?> POSTGRES = io.titan.test.TestContainers.postgres();


    record EquivalenceRun(
            PredicateReorderEquivalenceCaseModel.EquivalenceCase equivalenceCase,
            SelectPostgresSqlHarness.SelectRun originalRun,
            SelectPostgresSqlHarness.SelectRun rewrittenRun,
            String equivalenceMismatch,
            Path artifactDir
    ) {
    }

    EquivalenceRun run(PredicateReorderEquivalenceProfile profile, long seed, Path tempDir) throws Exception {
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(tempDir, "tempDir");

        Path artifactRoot = Files.createDirectories(tempDir.resolve(profile.id() + "-seed-" + Long.toUnsignedString(seed)));
        PredicateReorderEquivalenceCaseModel.EquivalenceCase equivalenceCase = switch (profile) {
            case PREDICATE_REORDER -> new PredicateReorderEquivalenceGenerator().generate(seed);
        };
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(),
                POSTGRES.getPassword())) {
            SelectPostgresSqlHarness sqlHarness = new SelectPostgresSqlHarness();
            var fixture = FixtureCatalog.accountsFixture();
            var originalRun = sqlHarness.runSelectCase(connection, equivalenceCase.originalCase(), fixture, artifactRoot.resolve("original"));
            var rewrittenRun = sqlHarness.runSelectCase(connection, equivalenceCase.rewrittenCase(), fixture, artifactRoot.resolve("rewritten"));
            String equivalenceMismatch = compare(originalRun, rewrittenRun);
            Files.writeString(artifactRoot.resolve("predicate-reorder-equivalence-case.json"), equivalenceCase.toStableJson());
            if (equivalenceMismatch != null) {
                Files.writeString(artifactRoot.resolve("equivalence-mismatch.txt"), equivalenceMismatch + System.lineSeparator());
            }
            return new EquivalenceRun(equivalenceCase, originalRun, rewrittenRun, equivalenceMismatch, artifactRoot);
        }
    }

    private static String compare(
            SelectPostgresSqlHarness.SelectRun originalRun,
            SelectPostgresSqlHarness.SelectRun rewrittenRun
    ) {
        if (originalRun.mismatchSummary() != null) {
            return "Original case mismatch vs reference: " + originalRun.mismatchSummary();
        }
        if (rewrittenRun.mismatchSummary() != null) {
            return "Rewritten case mismatch vs reference: " + rewrittenRun.mismatchSummary();
        }
        String expectedMismatch = new ReferenceResultComparison().summarizeMismatch(originalRun.expected(), rewrittenRun.expected());
        if (expectedMismatch != null) {
            return "Reference equivalence mismatch: " + expectedMismatch;
        }
        String actualMismatch = new ReferenceResultComparison().summarizeMismatch(originalRun.actual(), rewrittenRun.actual());
        if (actualMismatch != null) {
            return "SQL equivalence mismatch: " + actualMismatch;
        }
        return null;
    }
}
