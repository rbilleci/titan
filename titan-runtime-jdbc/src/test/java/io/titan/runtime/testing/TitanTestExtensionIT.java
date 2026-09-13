package io.titan.runtime.testing;

import org.junit.jupiter.api.Test;

import java.sql.ResultSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@TitanTest(targets = {DatabaseTarget.POSTGRESQL, DatabaseTarget.MYSQL})
// Docker-dependent (Testcontainers); runs via the integrationTest task, excluded from plain test (plan 4.5).
@org.junit.jupiter.api.Tag("docker")
class TitanTestExtensionIT {

    @Test
    void provisionsBothDialectsAndExposesConnections(TitanTestContext context) throws Exception {
        assertNotNull(context.connection(DatabaseTarget.POSTGRESQL));
        assertNotNull(context.connection(DatabaseTarget.MYSQL));

        try (ResultSet rs = context.connection(DatabaseTarget.POSTGRESQL)
                .createStatement()
                .executeQuery("SELECT COUNT(*) FROM titan_test_probe")) {
            rs.next();
            assertEquals(0, rs.getInt(1));
        }

        try (ResultSet rs = context.connection(DatabaseTarget.MYSQL)
                .createStatement()
                .executeQuery("SELECT COUNT(*) FROM titan_test_probe")) {
            rs.next();
            assertEquals(0, rs.getInt(1));
        }

        try (ResultSet rs = context.connection(DatabaseTarget.POSTGRESQL)
                .createStatement()
                .executeQuery("SELECT titan_runtime.java_mod(5, 2)")) {
            rs.next();
            assertEquals(1L, rs.getLong(1));
        }

        try (ResultSet rs = context.connection(DatabaseTarget.MYSQL)
                .createStatement()
                .executeQuery("SELECT titan_rt_java_mod(5, 2)")) {
            rs.next();
            assertEquals(1L, rs.getLong(1));
        }
    }
}
