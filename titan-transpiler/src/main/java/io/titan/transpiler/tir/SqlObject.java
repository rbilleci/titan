package io.titan.transpiler.tir;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * One concrete SQL object created by a generated artifact (plan 4.4, audit G-10).
 *
 * <p>The transpiler knows every object it emits — its kind, schema-qualified name, typed
 * parameter signature and (for triggers) the table it attaches to. Packaging consumes these
 * descriptors directly instead of regex-parsing the emitted SQL, which missed triggers and
 * events entirely, matched {@code CREATE} inside string literals, and split
 * {@code numeric(10,2)} parameters on the embedded comma.</p>
 *
 * @param kind       what the object is in the target catalog
 * @param schema     unquoted schema (PostgreSQL schema / MySQL database); may be empty for
 *                   objects created in the connection's default database
 * @param name       unquoted object name
 * @param parameters typed routine parameters in declaration order; empty for non-routines
 * @param returnType SQL return type for functions; empty otherwise
 * @param onTable    for triggers: the (possibly schema-qualified) table the trigger fires on;
 *                   empty otherwise
 */
public record SqlObject(
        Kind kind,
        String schema,
        String name,
        List<Parameter> parameters,
        String returnType,
        String onTable
) {
    public SqlObject {
        Objects.requireNonNull(kind, "kind");
        schema = schema == null ? "" : schema;
        Objects.requireNonNull(name, "name");
        parameters = List.copyOf(parameters == null ? List.of() : parameters);
        returnType = returnType == null ? "" : returnType;
        onTable = onTable == null ? "" : onTable;
    }

    public static SqlObject of(Kind kind, String schema, String name) {
        return new SqlObject(kind, schema, name, List.of(), "", "");
    }

    /** One typed routine parameter ({@code p_amount NUMERIC(10,2)} stays one parameter). */
    public record Parameter(String name, String type) {
        public Parameter {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(type, "type");
        }
    }

    /** {@code (p_course_id BIGINT, p_amount NUMERIC(10,2))} — derived, never re-parsed. */
    public String signature() {
        return "(" + parameters.stream()
                .map(parameter -> parameter.name() + " " + parameter.type())
                .collect(Collectors.joining(", ")) + ")";
    }

    public SqlObjectRef ref() {
        return new SqlObjectRef(kind, schema, name);
    }

    /** SQL object kinds Titan emits. Rendered lowercase in artifacts. */
    public enum Kind {
        PROCEDURE,
        FUNCTION,
        TRIGGER,
        EVENT,
        VIEW,
        TABLE,
        TYPE;

        public String label() {
            return name().toLowerCase(Locale.ROOT);
        }

        public static Optional<Kind> fromLabel(String label) {
            if (label == null || label.isBlank()) {
                return Optional.empty();
            }
            try {
                return Optional.of(valueOf(label.trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException e) {
                return Optional.empty();
            }
        }
    }
}
