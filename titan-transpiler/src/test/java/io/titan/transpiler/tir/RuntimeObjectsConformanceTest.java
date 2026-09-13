package io.titan.transpiler.tir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Pins {@code RuntimeStrategy.runtimeObjects()} to {@code runtimeMigrationSql()} (plan 4.4):
 * the declared structured descriptors and the SQL text are maintained side by side, and this
 * test fails when either gains or loses an object the other does not know about. (The regex
 * below lives in the test only — production packaging never parses the SQL.)
 */
class RuntimeObjectsConformanceTest {

    private static final Pattern CREATE_STATEMENT = Pattern.compile(
            "(?im)^\\s*CREATE\\s+(?:OR\\s+REPLACE\\s+)?(FUNCTION|PROCEDURE|TABLE)\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?([A-Za-z0-9_.]+)");

    @Test
    void everyDeclaredRuntimeObjectMatchesACreateStatementPerDialect() {
        for (DialectId dialectId : DialectId.values()) {
            RuntimeStrategy strategy = new DialectProviders().require(dialectId).runtimeStrategy();
            Set<String> created = createdObjects(strategy.runtimeMigrationSql());
            Set<String> declared = new LinkedHashSet<>();
            for (SqlObject object : strategy.runtimeObjects()) {
                String kind = switch (object.kind()) {
                    case FUNCTION -> "function";
                    case PROCEDURE -> "procedure";
                    case TABLE -> "table";
                    default -> object.kind().label();
                };
                declared.add(kind + ":" + qualifiedName(object));
            }
            assertEquals(created, declared,
                    dialectId + " runtimeObjects() must mirror the CREATE statements in runtimeMigrationSql()");
        }
    }

    @Test
    void runtimeRoutineSignaturesNameEveryParameterInTheSql() {
        for (DialectId dialectId : DialectId.values()) {
            RuntimeStrategy strategy = new DialectProviders().require(dialectId).runtimeStrategy();
            String sql = strategy.runtimeMigrationSql();
            for (SqlObject object : strategy.runtimeObjects()) {
                if (object.kind() != SqlObject.Kind.FUNCTION && object.kind() != SqlObject.Kind.PROCEDURE) {
                    continue;
                }
                String expectedHead = object.name() + "(" + (object.parameters().isEmpty()
                        ? ""
                        : object.parameters().get(0).name());
                assertTrue(sql.contains(expectedHead),
                        dialectId + " runtime SQL must declare " + expectedHead + "...) as described");
            }
        }
    }

    private static Set<String> createdObjects(String sql) {
        Set<String> created = new LinkedHashSet<>();
        Matcher matcher = CREATE_STATEMENT.matcher(sql);
        while (matcher.find()) {
            created.add(matcher.group(1).toLowerCase() + ":" + matcher.group(2).toLowerCase());
        }
        return created;
    }

    private static String qualifiedName(SqlObject object) {
        if (object.schema() == null || object.schema().isBlank()) {
            return object.name();
        }
        return object.schema() + "." + object.name();
    }
}
