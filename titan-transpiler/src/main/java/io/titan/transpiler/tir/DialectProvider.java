package io.titan.transpiler.tir;

/**
 * Everything the transpilation pipeline needs to target one SQL dialect (plan 1.3 / 3.1 / 3.4).
 *
 * <p>Adding a dialect means implementing this interface (plus the strategy objects it exposes)
 * in new files and registering the instance in {@link DialectProviders}; see
 * {@code docs/adding-a-dialect.md} for the full checklist.</p>
 */
public interface DialectProvider {
    DialectId id();

    /**
     * Compile-time capability descriptor for this dialect (plan 1.3/3.1/3.4). Each provider owns
     * its canonical descriptor — there is no central per-dialect switch to extend.
     */
    DialectCapabilities capabilities();

    SqlEmitter emitter();

    /**
     * Structured descriptors for the SQL objects each artifact kind creates (plan 4.4, audit
     * G-10) — the packaging layer consumes these instead of regex-parsing emitted SQL.
     */
    ArtifactDescriber artifactDescriber();

    TypeMapper typeMapper();

    RuntimeStrategy runtimeStrategy();

    MigrationStrategy migrationStrategy();
}
