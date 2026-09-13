package io.titan.transpiler.jdbc;

import io.titan.transpiler.EntryPointKind;
import java.util.List;

/**
 * The per-method JDBC compatibility verdict: the entry point's identity, the usages the recognizer
 * found in its body, and the rolled-up classification.
 *
 * <p>The {@link #rollup()} is {@link JdbcClassification#REJECTED} if any usage was rejected, else
 * {@link JdbcClassification#PASSTHROUGH} if any usage was passthrough, else
 * {@link JdbcClassification#TRANSPILABLE} (the empty-usage method is vacuously TRANSPILABLE).</p>
 *
 * @param className the entry point's enclosing-type name (source-local qualified)
 * @param method    the entry point method's simple name
 * @param file      the source file name
 * @param line      the entry point's declaration line
 * @param kind      the entry-point kind (procedure/function/trigger/job) — decides the I-10 verdict
 * @param usages    the recognized JDBC usages, in source order
 */
public record MethodJdbcReport(
        String className,
        String method,
        String file,
        long line,
        EntryPointKind kind,
        List<JdbcUsage> usages
) {
    public MethodJdbcReport {
        usages = usages == null ? List.of() : List.copyOf(usages);
    }

    /** REJECTED if any usage rejected, else PASSTHROUGH if any, else TRANSPILABLE. */
    public JdbcClassification rollup() {
        boolean anyPassthrough = false;
        for (JdbcUsage usage : usages) {
            if (usage.classification() == JdbcClassification.REJECTED) {
                return JdbcClassification.REJECTED;
            }
            if (usage.classification() == JdbcClassification.PASSTHROUGH) {
                anyPassthrough = true;
            }
        }
        return anyPassthrough ? JdbcClassification.PASSTHROUGH : JdbcClassification.TRANSPILABLE;
    }
}
