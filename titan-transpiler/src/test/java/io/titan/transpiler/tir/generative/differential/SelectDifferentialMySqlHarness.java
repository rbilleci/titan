package io.titan.transpiler.tir.generative.differential;

import io.titan.transpiler.tir.generative.shared.FixtureCatalog;
import io.titan.transpiler.tir.generative.shared.SelectCaseModel;
import io.titan.transpiler.tir.generative.shared.SelectMySqlSqlHarness;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import org.testcontainers.containers.MySQLContainer;

/** MySQL twin of {@link SelectDifferentialHarness} (plan 5.3). */
final class SelectDifferentialMySqlHarness {

    private static final MySQLContainer<?> MYSQL = io.titan.test.TestContainers.mysql();

    SelectMySqlSqlHarness.SelectRun run(SelectDifferentialProfile profile, long seed, Path tempDir) throws Exception {
        Path artifactRoot = Files.createDirectories(tempDir.resolve(profile.id() + "-mysql-seed-" + Long.toUnsignedString(seed)));
        SelectCaseModel.SelectCase selectCase = switch (profile) {
            case BASIC_SELECT -> new SelectDifferentialGenerator().generate(seed);
        };
        FixtureCatalog.FixtureTable fixture = FixtureCatalog.accountsFixture();
        try (Connection connection = DriverManager.getConnection(
                MYSQL.getJdbcUrl(),
                MYSQL.getUsername(),
                MYSQL.getPassword())) {
            return new SelectMySqlSqlHarness().runSelectCase(connection, selectCase, fixture, artifactRoot);
        }
    }
}
