package io.titan.transpiler.jdbc;

import com.sun.source.tree.ExpressionTree;
import java.util.List;

/**
 * The recovered <i>skeleton</i> of a non-constant JDBC SQL-text argument (WS-C Phase 3, design
 * contract §3): an ordered list of {@link Fragment}s — {@link Constant} text runs and {@link Hole}s
 * — modelling the Java string construction that builds the SQL. <b>There is no SQL parser.</b> The
 * skeleton is recovered from the Java AST ({@code +}-concatenation, {@code String.format}/{@code
 * join}/{@code repeat}, {@code StringBuilder.append}-chains, a recognized {@code placeholders(n)}
 * helper) by {@link JdbcSqlSkeletonRecognizer}; the only SQL-text inspection is a local edge-check at
 * a hole (e.g. "does the preceding constant end with {@code FROM }?").
 *
 * <p>Each runtime {@link Hole} carries a {@link HoleKind} classification (design contract D1/D2):</p>
 * <ul>
 *   <li>{@link HoleKind#PLACEHOLDER} — a generated {@code ?}/separator run ({@code ?,?,…}); it is
 *       <b>value-bindable</b>: the {@code ?}s are bound by the statement's own {@code setXxx} ordinals
 *       and no value reaches the text. The hole carries its rendered placeholder text (with the
 *       separators) so {@link #recoveredSql()} reproduces it byte-for-byte.</li>
 *   <li>{@link HoleKind#VALUE} — a runtime value/param/local spliced into a value position
 *       ({@code "… = " + id}); <b>value-bindable</b>: it lowers to a single {@code ?} plus a
 *       synthesized bind, so the value is parameterized and never reaches the emitted text (design
 *       contract D3).</li>
 *   <li>{@link HoleKind#IDENTIFIER} — a runtime identifier splice (table/column after
 *       {@code FROM}/{@code JOIN}/{@code INTO}/{@code UPDATE}/{@code ORDER BY}); <b>not</b>
 *       value-bindable (it reaches the text). Rung 1 does not emit it — strict rejects, permissive
 *       defers to Rung 3.</li>
 *   <li>{@link HoleKind#RAW_FRAGMENT} — anything the recognizer cannot prove is one of the above;
 *       the FAIL-SAFE catch-all. <b>Not</b> value-bindable. The security invariant: when in doubt,
 *       classify {@code RAW_FRAGMENT} and reject — a raw value or identifier must NEVER reach the
 *       emitted SQL text.</li>
 * </ul>
 *
 * <p><b>Rung 1 acceptance line</b> (design contract D2): the skeleton transpiles via the existing
 * {@link io.titan.transpiler.tir.RawSql} substrate <i>only</i> when {@link #allHolesValueBindable()}
 * — every hole is {@link HoleKind#VALUE} or {@link HoleKind#PLACEHOLDER}. An {@link HoleKind#IDENTIFIER}
 * or {@link HoleKind#RAW_FRAGMENT} hole keeps the current strict-reject / permissive-defer behavior.</p>
 *
 * <p><b>Bind ordering.</b> {@link #recoveredSql()} emits a fixed run of {@code ?} placeholders
 * (constants' own {@code ?}s, PLACEHOLDER runs' {@code ?}s, and one {@code ?} per VALUE hole), in
 * left-to-right text order. The downstream {@code EXECUTE … USING} binds the Nth {@code ?} to the Nth
 * {@code USING} argument, so the lowerer builds the bind-name list in that same left-to-right order: a
 * constant/PLACEHOLDER {@code ?} consumes the next {@code setXxx} ordinal; a VALUE hole's {@code ?}
 * consumes its synthesized bind. The skeleton itself carries no bind names — it is the structural model
 * the lowerer walks ({@link #fragments()}) to assemble them.</p>
 */
public final class JdbcSqlSkeleton {

    /** The classification of a runtime hole; see the type-level docs. */
    public enum HoleKind {
        /** A generated {@code ?}/separator run, bound by the statement's {@code setXxx} ordinals. */
        PLACEHOLDER,
        /** A runtime value spliced into a value position — lowers to {@code ?} + a synthesized bind. */
        VALUE,
        /** A runtime identifier splice — not bindable; Rung 1 does not emit it. */
        IDENTIFIER,
        /** The FAIL-SAFE catch-all for anything unprovable — not bindable; reject. */
        RAW_FRAGMENT
    }

    /** A fragment of the recovered skeleton: either constant text or a runtime hole. */
    public sealed interface Fragment permits Constant, Hole {
    }

    /** A run of constant SQL text recovered from the Java construction (may itself contain {@code ?}). */
    public record Constant(String text) implements Fragment {
        public Constant {
            if (text == null) {
                throw new IllegalArgumentException("constant text must not be null");
            }
        }
    }

    /**
     * A runtime hole. For a {@link HoleKind#PLACEHOLDER} run, {@link #placeholderText} is the exact
     * {@code ?}/separator text it renders ({@code "?,?,?"}) and {@link #bindExpr} is {@code null}. For a
     * {@link HoleKind#VALUE} hole, {@link #placeholderText} is a single {@code "?"} and {@link #bindExpr}
     * is the Java expression to bind (the {@code "… = " + bindExpr} operand). For
     * {@link HoleKind#IDENTIFIER}/{@link HoleKind#RAW_FRAGMENT}, {@link #placeholderText} is empty (Rung
     * 1 never emits them — the caller rejects first) and {@link #bindExpr} is the un-bindable operand
     * (kept for diagnostics/later rungs).
     */
    public record Hole(HoleKind kind, ExpressionTree bindExpr, String placeholderText) implements Fragment {
        public Hole {
            if (kind == null || placeholderText == null) {
                throw new IllegalArgumentException("hole kind/placeholderText must not be null");
            }
        }

        /** A value hole over {@code bindExpr} (one synthesized {@code ?}). */
        public static Hole value(ExpressionTree bindExpr) {
            return new Hole(HoleKind.VALUE, bindExpr, "?");
        }

        /** A placeholder/separator run rendering exactly {@code placeholderText} (e.g. {@code "?,?,?"}). */
        public static Hole placeholders(String placeholderText) {
            return new Hole(HoleKind.PLACEHOLDER, null, placeholderText);
        }

        /** An identifier splice over {@code bindExpr} (Rung 1 emits no placeholder for it). */
        public static Hole identifier(ExpressionTree bindExpr) {
            return new Hole(HoleKind.IDENTIFIER, bindExpr, "");
        }

        /** The FAIL-SAFE catch-all over {@code bindExpr} (Rung 1 emits no placeholder for it). */
        public static Hole rawFragment(ExpressionTree bindExpr) {
            return new Hole(HoleKind.RAW_FRAGMENT, bindExpr, "");
        }

        /** The number of {@code ?} characters this hole contributes to the recovered text. */
        public int placeholderCount() {
            return (int) placeholderText.chars().filter(c -> c == '?').count();
        }
    }

    private final List<Fragment> fragments;

    public JdbcSqlSkeleton(List<Fragment> fragments) {
        if (fragments == null) {
            throw new IllegalArgumentException("fragments must not be null");
        }
        this.fragments = List.copyOf(fragments);
    }

    /** The ordered fragments of the recovered construction. */
    public List<Fragment> fragments() {
        return fragments;
    }

    /**
     * The recovered SQL text with every value-bindable hole rendered as {@code ?} placeholders
     * (PLACEHOLDER runs as their {@code ?}/separator text, VALUE holes as a single {@code ?}). An
     * {@link HoleKind#IDENTIFIER}/{@link HoleKind#RAW_FRAGMENT} hole contributes <b>nothing</b> here —
     * Rung 1 never emits such a skeleton (the caller rejects first via {@link #allHolesValueBindable()}).
     */
    public String recoveredSql() {
        StringBuilder sql = new StringBuilder();
        for (Fragment fragment : fragments) {
            if (fragment instanceof Constant constant) {
                sql.append(constant.text());
            } else if (fragment instanceof Hole hole) {
                sql.append(hole.placeholderText());
            }
        }
        return sql.toString();
    }

    /**
     * True when every {@link Hole} is value-bindable ({@link HoleKind#VALUE} or
     * {@link HoleKind#PLACEHOLDER}) — the design contract D2 acceptance line for Rung 1. A skeleton
     * with no holes is degenerate (the constant path handles it) but is trivially value-bindable.
     */
    public boolean allHolesValueBindable() {
        for (Fragment fragment : fragments) {
            if (fragment instanceof Hole hole
                    && hole.kind() != HoleKind.VALUE && hole.kind() != HoleKind.PLACEHOLDER) {
                return false;
            }
        }
        return true;
    }

    /** Whether the skeleton has at least one runtime hole (i.e. it is genuinely non-constant). */
    public boolean hasHole() {
        return fragments.stream().anyMatch(Hole.class::isInstance);
    }

    /** The first non-value-bindable hole ({@link HoleKind#IDENTIFIER}/{@link HoleKind#RAW_FRAGMENT}), if any. */
    public java.util.Optional<Hole> firstUnbindableHole() {
        for (Fragment fragment : fragments) {
            if (fragment instanceof Hole hole
                    && hole.kind() != HoleKind.VALUE && hole.kind() != HoleKind.PLACEHOLDER) {
                return java.util.Optional.of(hole);
            }
        }
        return java.util.Optional.empty();
    }
}
