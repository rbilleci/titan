package io.titan.transpiler.tir.generative.mutation;

import io.titan.transpiler.tir.generative.shared.SelectPostgresSqlHarness;
import io.titan.transpiler.tir.generative.shared.FixtureCatalog;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import org.testcontainers.containers.PostgreSQLContainer;

final class BasicMutationHarness {

    private static final PostgreSQLContainer<?> POSTGRES = io.titan.test.TestContainers.postgres();


    record MutationRun(
            MutationCaseModel.MutationCase mutationCase,
            SelectPostgresSqlHarness.SelectRun selectRun,
            Path artifactDir
    ) {
    }

    MutationRun run(MutationProfile profile, long seed, Path tempDir) throws Exception {
        return run(profile, seed, null, tempDir);
    }

    MutationRun run(
            MutationProfile profile,
            long seed,
            MutationCaseModel.MutatorId mutatorOverride,
            Path tempDir
    ) throws Exception {
        Path artifactRoot = Files.createDirectories(tempDir.resolve(profile.id() + "-seed-" + Long.toUnsignedString(seed)));
        MutationCaseModel.MutationCase generated = new BasicMutationGenerator().generate(profile, seed, mutatorOverride);
        FixtureCatalog.FixtureTable fixture = FixtureCatalog.accountsFixture();
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(),
                POSTGRES.getPassword())) {
            var selectRun = new SelectPostgresSqlHarness().runSelectCase(
                    connection,
                    generated.mutatedCase(),
                    fixture,
                    artifactRoot);
            MutationCaseModel.MutationClassification classification = selectRun.mismatchSummary() == null
                    ? MutationCaseModel.MutationClassification.STABLE_PASS
                    : MutationCaseModel.MutationClassification.SEMANTIC_MISMATCH;
            MutationCaseModel.MutationCase completed = MutationCaseModel.mutationCase(
                    generated.profileId(),
                    generated.baseProfileId(),
                    generated.baseSeed(),
                    generated.mutatorId(),
                    generated.mutationParameters(),
                    generated.baseCase(),
                    generated.mutatedCase(),
                    classification,
                    generated.reductionSteps());
            new BasicMutationArtifactWriter().write(artifactRoot, completed, selectRun);
            return new MutationRun(completed, selectRun, artifactRoot);
        }
    }
}
