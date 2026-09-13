package io.titan.transpiler.tir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class LoweredSelectSqlEmitter {

    private static final Pattern POSTGRES_BODY_PATTERN = Pattern.compile("BEGIN\\s+(.*?)\\s+END;", Pattern.DOTALL);

    public String emitPostgresSelectSql(String javaSource, Path tempDir) throws Exception {
        Path sourceFile = tempDir.resolve("LoweredSelectSqlEmitterInput.java");
        Files.writeString(sourceFile, javaSource);

        List<TranspilationPipeline.GeneratedSql> generated = new TranspilationPipeline().transpile(
                List.of(sourceFile),
                List.of(),
                List.of("postgresql"),
                List.of("app"),
                true);
        String sql = generated.getFirst().sql();
        Matcher matcher = POSTGRES_BODY_PATTERN.matcher(sql);
        if (!matcher.find()) {
            throw new IllegalStateException("Could not extract emitted PostgreSQL routine body from generated SQL:\n" + sql);
        }
        return toStandaloneSelect(matcher.group(1).trim());
    }

    /**
     * G1 (spike B1): a discarded {@code select(...).fetch()} now lowers to {@code PERFORM} in the
     * PostgreSQL routine body (a bare {@code SELECT} raises "no destination for result data" at
     * execute). This harness authors exactly that discarded shape but then runs the extracted query
     * as a standalone top-level statement, where {@code PERFORM} is invalid — so it maps the
     * routine-body {@code PERFORM} form back to an executable {@code SELECT}. The plain case swaps
     * the leading keyword; the CTE / set-operation case the emitter wraps as
     * {@code * FROM (<query>) AS __titan_discarded} is unwrapped back to {@code <query>}. A body
     * that is already a {@code SELECT}/{@code WITH} is returned unchanged.
     */
    private static String toStandaloneSelect(String routineBody) {
        String body = routineBody.endsWith(";") ? routineBody.substring(0, routineBody.length() - 1).trim() : routineBody;
        String wrapPrefix = "PERFORM * FROM (";
        String wrapSuffix = ") AS __titan_discarded";
        if (body.startsWith(wrapPrefix) && body.endsWith(wrapSuffix)) {
            return body.substring(wrapPrefix.length(), body.length() - wrapSuffix.length()).trim();
        }
        if (body.startsWith("PERFORM ")) {
            return "SELECT " + body.substring("PERFORM ".length());
        }
        return body;
    }

    /**
     * The MySQL twin of {@link #emitPostgresSelectSql} (plan 5.3): runs the same full
     * production pipeline with {@code targets=["mysql"]} and extracts the emitted SELECT
     * statement from the generated routine. Unlike the PostgreSQL body (which contains only
     * the SELECT), the MySQL routine body wraps the statement in the time-zone save/restore
     * scaffolding and the routine-level EXIT handler, so the extraction picks the single
     * emitted {@code SELECT}/{@code WITH} statement line out of the body instead of taking
     * everything between BEGIN and END.
     */
    public String emitMySqlSelectSql(String javaSource, Path tempDir) throws Exception {
        Path sourceFile = tempDir.resolve("LoweredSelectSqlEmitterInput.java");
        Files.writeString(sourceFile, javaSource);

        List<TranspilationPipeline.GeneratedSql> generated = new TranspilationPipeline().transpile(
                List.of(sourceFile),
                List.of(),
                List.of("mysql"),
                List.of("app"),
                true);
        String sql = generated.getFirst().sql();
        String selectStatement = null;
        for (String line : sql.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("SELECT ") || trimmed.startsWith("WITH ")) {
                if (selectStatement != null) {
                    throw new IllegalStateException(
                            "Expected exactly one emitted SELECT statement in the MySQL routine body, found more:\n" + sql);
                }
                selectStatement = trimmed.endsWith(";") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
            }
        }
        if (selectStatement == null) {
            throw new IllegalStateException("Could not extract emitted MySQL SELECT statement from generated SQL:\n" + sql);
        }
        return selectStatement;
    }
}
