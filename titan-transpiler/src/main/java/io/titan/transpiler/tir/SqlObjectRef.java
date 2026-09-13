package io.titan.transpiler.tir;

import java.util.Objects;

/**
 * Identity of a generated SQL object — a dependency edge target (plan 4.4, audit G-10).
 *
 * <p>Edges are produced from the entry-point call graph and the typed TIR (record types, enum
 * lookup helpers, shared views actually referenced), never inferred by name-substring matching
 * over emitted SQL ({@code get_user} inside {@code get_user_orders} is not an edge).</p>
 */
public record SqlObjectRef(SqlObject.Kind kind, String schema, String name) {
    public SqlObjectRef {
        Objects.requireNonNull(kind, "kind");
        schema = schema == null ? "" : schema;
        Objects.requireNonNull(name, "name");
    }
}
