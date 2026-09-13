package io.titan.transpiler.tir.generative.differential;

import io.titan.transpiler.tir.generative.shared.SelectPostgresSqlHarness;
import io.titan.transpiler.tir.generative.shared.SelectCaseModel;
import io.titan.transpiler.tir.generative.shared.FixtureCatalog;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import org.testcontainers.containers.PostgreSQLContainer;

final class SelectDifferentialHarness {

    private static final PostgreSQLContainer<?> POSTGRES = io.titan.test.TestContainers.postgres();


    SelectPostgresSqlHarness.SelectRun run(SelectDifferentialProfile profile, long seed, Path tempDir) throws Exception {
        Path artifactRoot = Files.createDirectories(tempDir.resolve(profile.id() + "-seed-" + Long.toUnsignedString(seed)));
        SelectCaseModel.SelectCase selectCase = switch (profile) {
            case BASIC_SELECT -> new SelectDifferentialGenerator().generate(seed);
        };
        FixtureCatalog.FixtureTable fixture = FixtureCatalog.accountsFixture();
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(),
                POSTGRES.getPassword())) {
            return new SelectPostgresSqlHarness().runSelectCase(connection, selectCase, fixture, artifactRoot);
        }
    }
}
