package io.titan.gradle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.titan.transpiler.tir.SqlObject;
import java.util.List;
import org.junit.jupiter.api.Test;

final class TitanAdditionalRuntimeSchemaTest {
    @Test
    void mysqlRuntimeCopiesHelpersIntoAdditionalSchemaAndRestoresDefault() {
        String migration = TitanPackagedArtifacts.runtimeMigrationSqlForDialect(
                "mysql", List.of("candidate_123"));
        assertTrue(migration.startsWith("CREATE DATABASE IF NOT EXISTS `public`;\nUSE `public`;\n"));
        assertTrue(migration.contains("CREATE DATABASE IF NOT EXISTS `candidate_123`;\nUSE `candidate_123`;\n"));
        assertTrue(migration.endsWith("USE `public`;\n"));
        assertEquals(2, occurrences(migration, "CREATE FUNCTION titan_rt_java_int_div"));

        List<SqlObject> objects = TitanPackagedArtifacts.runtimeObjectsForDialect(
                "mysql", List.of("candidate_123"));
        assertTrue(objects.stream().anyMatch(object -> object.schema().isBlank()
                && object.name().equals("titan_rt_java_int_div")));
        assertTrue(objects.stream().anyMatch(object -> object.schema().equals("candidate_123")
                && object.name().equals("titan_rt_java_int_div")));
        assertEquals(1, objects.stream().filter(object -> object.name().equals("telemetry")).count());
    }

    @Test
    void rejectsUnsafeAndNonMysqlRuntimeSchemas() {
        assertThrows(IllegalArgumentException.class, () -> TitanPackagedArtifacts.runtimeMigrationSqlForDialect(
                "mysql", List.of("candidate`; DROP DATABASE public;")));
        assertThrows(IllegalArgumentException.class, () -> TitanPackagedArtifacts.runtimeMigrationSqlForDialect(
                "postgresql", List.of("candidate_123")));
    }

    private static int occurrences(String source, String value) {
        int count = 0;
        for (int index = source.indexOf(value); index >= 0; index = source.indexOf(value, index + value.length())) {
            count++;
        }
        return count;
    }
}
