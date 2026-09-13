package io.titan.transpiler.jdbc;

import io.titan.transpiler.ParsedSources;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeMirror;

/**
 * Resolved-type predicates for the {@code java.sql}/{@code javax.sql} handle types the JDBC
 * recognizer walks ({@code docs/transpilable-jdbc-subset.md} §2.1). Identity is by the resolved
 * {@link TypeMirror}'s fully-qualified name — never source text or variable-name heuristics —
 * exactly as {@code StatementLowerer} resolves catch types and {@code JavaTypeToTirMapper}
 * resolves declared types (via {@code types().asElement(...).getQualifiedName()}).
 *
 * <p>A type is matched by its erasure's FQN, so {@code ResultSet} parameterizations and subtypes'
 * declared static type still resolve to the canonical {@code java.sql.*} name the compiler records.
 * Matching is exact-FQN (not assignability) to mirror the spec's "by resolved TypeMirror" rule and
 * to avoid pulling user subclasses of these interfaces into scope implicitly.</p>
 */
public final class JdbcTypeOracle {

    public static final String CONNECTION = "java.sql.Connection";
    public static final String STATEMENT = "java.sql.Statement";
    public static final String PREPARED_STATEMENT = "java.sql.PreparedStatement";
    public static final String CALLABLE_STATEMENT = "java.sql.CallableStatement";
    public static final String RESULT_SET = "java.sql.ResultSet";
    public static final String RESULT_SET_METADATA = "java.sql.ResultSetMetaData";
    public static final String DATA_SOURCE = "javax.sql.DataSource";
    public static final String SQL_EXCEPTION = "java.sql.SQLException";

    private final ParsedSources parsedSources;

    public JdbcTypeOracle(ParsedSources parsedSources) {
        if (parsedSources == null) {
            throw new IllegalArgumentException("parsedSources must not be null");
        }
        this.parsedSources = parsedSources;
    }

    /** The erased FQN of {@code type}, or {@code null} if it does not resolve to a named type. */
    public String qualifiedName(TypeMirror type) {
        if (type == null) {
            return null;
        }
        TypeMirror erased = parsedSources.types().erasure(type);
        if (!(parsedSources.types().asElement(erased) instanceof TypeElement element)) {
            return null;
        }
        return element.getQualifiedName().toString();
    }

    private boolean isNamed(TypeMirror type, String fqn) {
        return fqn.equals(qualifiedName(type));
    }

    public boolean isConnection(TypeMirror type) {
        return isNamed(type, CONNECTION);
    }

    public boolean isDataSource(TypeMirror type) {
        return isNamed(type, DATA_SOURCE);
    }

    /** True for {@code java.sql.Connection} or {@code javax.sql.DataSource} (elidable signature param). */
    public boolean isConnectionLike(TypeMirror type) {
        return isConnection(type) || isDataSource(type);
    }

    /** True for {@code PreparedStatement} or {@code CallableStatement} (both extend it). */
    public boolean isPreparedStatement(TypeMirror type) {
        return isNamed(type, PREPARED_STATEMENT) || isNamed(type, CALLABLE_STATEMENT);
    }

    public boolean isCallableStatement(TypeMirror type) {
        return isNamed(type, CALLABLE_STATEMENT);
    }

    /** True for a plain {@code java.sql.Statement} (exact — not the PreparedStatement subtype). */
    public boolean isPlainStatement(TypeMirror type) {
        return isNamed(type, STATEMENT);
    }

    /** True for any of {@code Statement}/{@code PreparedStatement}/{@code CallableStatement}. */
    public boolean isAnyStatement(TypeMirror type) {
        return isNamed(type, STATEMENT) || isPreparedStatement(type);
    }

    public boolean isResultSet(TypeMirror type) {
        return isNamed(type, RESULT_SET);
    }

    /**
     * True for {@code java.sql.ResultSetMetaData} — the generic-reader metadata handle (WS-C Phase 3
     * Rung 5, the unknown-shape carrier). A {@code ResultSetMetaData} local is elided/exempt exactly
     * like the other JDBC handles: it carries no value into the routine, it is the driver of the
     * metadata-driven column walk the carrier lowering subsumes. Outside the recognized carrier shape a
     * {@code ResultSetMetaData} read is still rejected at lowering (no typed mapping for it).
     */
    public boolean isResultSetMetaData(TypeMirror type) {
        return isNamed(type, RESULT_SET_METADATA);
    }

    /** True for {@code java.sql.SQLException} (the I-9 {@code catch(SQLException)} type, §3.5). */
    public boolean isSqlException(TypeMirror type) {
        return isNamed(type, SQL_EXCEPTION);
    }

    /** True for any {@code java.sql}/{@code javax.sql} handle the recognizer elides (no DeclareVariable). */
    public boolean isJdbcHandle(TypeMirror type) {
        return isConnectionLike(type) || isAnyStatement(type) || isResultSet(type)
                || isResultSetMetaData(type);
    }
}
