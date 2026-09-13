package io.titan.transpiler.jdbc;

/**
 * The transpiler's <b>internal</b> resolved raw-SQL safety mode for a JDBC method scope
 * ({@code docs/transpilable-jdbc-subset.md} §4).
 *
 * <ul>
 *   <li>{@link #STRICT} — injection-proof by construction: SQL text passed to
 *       {@code prepareStatement}/{@code createStatement(...).execute(...)} must be a compile-time
 *       constant. Splicing a runtime value or identifier is a hard {@code TITAN-E004}.</li>
 *   <li>{@link #PERMISSIVE} — raw SQL that strict mode would reject transpiles faithfully as a
 *       {@code RawSql} passthrough, for compatibility with existing codebases. Value bindings stay
 *       parameterized either way.</li>
 * </ul>
 *
 * <p>This enum is owned by the transpiler so the lowering pipeline carries no dependency on the
 * optional {@code titan-dsl} front-end. It is the resolved result, not the user-facing knob: the
 * {@code @titan.dsl.SqlSafety} source annotation is read off the javac AST <b>by name</b> (see
 * {@link SqlSafetyResolver#parseEnumValue}, which maps the annotation's enum-constant name onto these
 * constants), and the build-level {@code sqlSafety} string is parsed by
 * {@link SqlSafetyResolver#parseBuildLevel}. The constant names here must therefore stay in lock-step
 * with {@code titan.dsl.SqlSafetyMode}.</p>
 */
public enum SqlSafetyMode {
    STRICT,
    PERMISSIVE
}
