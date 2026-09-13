package io.titan.runtime.testing;

/**
 * Row-set comparison modes for the equivalence oracle (audit R-5).
 *
 * <p>The caller chooses the mode. {@link #UNORDERED} is the default everywhere a mode is not
 * given explicitly, because SQL result order is unspecified unless an {@code ORDER BY} is
 * provable — and the oracle cannot prove one from a lambda. Use {@link #ORDERED} only for
 * queries whose ordering is part of the asserted contract.</p>
 */
public enum ComparisonMode {
    /** Rows must match pairwise in encounter order. */
    ORDERED,
    /** Rows must match as a multiset: same rows with the same multiplicities, any order. */
    UNORDERED
}
