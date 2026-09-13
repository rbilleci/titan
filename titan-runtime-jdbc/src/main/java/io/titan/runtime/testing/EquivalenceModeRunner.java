package io.titan.runtime.testing;

import io.titan.runtime.jdbc.JdbcExecutor;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * Runs both Java-mode and SQL-mode assertions and fails fast when results diverge.
 *
 * <p>Comparison goes through the {@link EquivalenceOracle} (audit R-5): values are normalized
 * (integral widening, scale-insensitive {@code BigDecimal}, temporal/char unification) before
 * comparison, row sets compare {@linkplain ComparisonMode#UNORDERED unordered} by default —
 * order is only asserted when the caller passes {@link ComparisonMode#ORDERED}, i.e. when an
 * {@code ORDER BY} makes ordering part of the contract — and a mismatch reports the first
 * divergence (row, column, both values, both types) instead of two opaque {@code toString}s.</p>
 */
public final class EquivalenceModeRunner {
    private final JavaModeRunner javaModeRunner;
    private final SqlModeRunner sqlModeRunner;

    EquivalenceModeRunner(JavaModeRunner javaModeRunner, SqlModeRunner sqlModeRunner) {
        this.javaModeRunner = Objects.requireNonNull(javaModeRunner, "javaModeRunner");
        this.sqlModeRunner = Objects.requireNonNull(sqlModeRunner, "sqlModeRunner");
    }

    public <T> T compareScalar(JavaModeRunner.Work<T> javaWork,
                               Function<SqlModeRunner, T> sqlWork,
                               String assertionLabel) {
        Objects.requireNonNull(javaWork, "javaWork");
        Objects.requireNonNull(sqlWork, "sqlWork");
        T javaResult = javaModeRunner.run(javaWork);
        T sqlResult = sqlWork.apply(sqlModeRunner);
        failOnDivergence(EquivalenceOracle.compareScalars(javaResult, sqlResult),
                assertionLabel, javaResult, sqlResult);
        return javaResult;
    }

    /**
     * Compares row sets {@linkplain ComparisonMode#UNORDERED unordered} — the safe default when
     * no {@code ORDER BY} is provable from the work the caller hands in.
     */
    public <T> List<T> compareRows(JavaModeRunner.Work<List<T>> javaWork,
                                   Function<SqlModeRunner, List<T>> sqlWork,
                                   String assertionLabel) {
        return compareRows(javaWork, sqlWork, assertionLabel, ComparisonMode.UNORDERED);
    }

    /** Compares row sets in the caller-chosen {@link ComparisonMode}. */
    public <T> List<T> compareRows(JavaModeRunner.Work<List<T>> javaWork,
                                   Function<SqlModeRunner, List<T>> sqlWork,
                                   String assertionLabel,
                                   ComparisonMode mode) {
        Objects.requireNonNull(javaWork, "javaWork");
        Objects.requireNonNull(sqlWork, "sqlWork");
        Objects.requireNonNull(mode, "mode");
        List<T> javaResult = javaModeRunner.run(javaWork);
        List<T> sqlResult = sqlWork.apply(sqlModeRunner);
        failOnDivergence(EquivalenceOracle.compareRows(javaResult, sqlResult, mode),
                assertionLabel, javaResult, sqlResult);
        return javaResult;
    }

    private static void failOnDivergence(Optional<EquivalenceOracle.Divergence> divergence,
                                         String assertionLabel, Object javaResult, Object sqlResult) {
        if (divergence.isPresent()) {
            throw new AssertionError("Titan equivalence mismatch [" + assertionLabel + "]"
                    + "\n" + divergence.get().describe()
                    + "\njava-mode: " + javaResult
                    + "\nsql-mode: " + sqlResult);
        }
    }

    public JdbcExecutor jdbc() {
        return javaModeRunner.jdbc();
    }

    public SqlModeRunner sql() {
        return sqlModeRunner;
    }
}
