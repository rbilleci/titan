package io.titan.transpiler.tir;

import java.util.List;

public interface RuntimeStrategy {
    String runtimeNamespace();

    boolean supportsNativeArrays();

    String runtimeMigrationSql();

    /**
     * Structured descriptors for every SQL object {@link #runtimeMigrationSql()} creates
     * (plan 4.4, audit G-10). The packaging layer inventories the runtime bundle from this list
     * instead of regex-parsing the migration text; {@code RuntimeObjectsConformanceTest} pins
     * each declared object to a {@code CREATE} statement in the SQL so the two cannot drift.
     */
    List<SqlObject> runtimeObjects();
}
