package io.titan.transpiler.jdbc;

import io.titan.introspect.SchemaModel;
import java.util.Locale;
import java.util.Optional;

/**
 * Resolves the single auto-increment/identity key column of a constant {@code INSERT INTO <table>}
 * statement from the introspected {@link SchemaModel} catalog — the column the JDBC ordinal {@code 1}
 * in {@code getGeneratedKeys().getLong(1)} stands for (§6.3 / I-7). The JDBC ordinal {@code 1} does
 * <i>not</i> name a column, so the column name is recovered here: the INSERT's target table is found by
 * a bounded, parser-free scan of the constant INSERT text, then matched against the catalog and reduced
 * to its <b>single</b> auto-increment column.
 *
 * <p><b>Strict by construction — never the wrong table, never a guessed column.</b> The scanner mirrors
 * {@code MySqlEmitter.injectIntoTargets}' char-by-char scanner: it skips string literals,
 * {@code "double-quoted"}/{@code `back-ticked`} identifiers and {@code --} / {@code /* *}{@code /}
 * comments, tracks parenthesis depth, and matches the {@code INSERT}/{@code INTO} keywords only as
 * whole words at depth 0. It accepts only the canonical {@code INSERT [IGNORE] INTO <table>} /
 * {@code INSERT INTO <schema>.<table>} prefix; anything it cannot read with confidence (a non-INSERT
 * statement, {@code INSERT ... SELECT} with no plain table, a CTE-prefixed {@code WITH ... INSERT}, a
 * variable-built name, …) yields {@link Optional#empty()} and the caller rejects (I-R8) rather than
 * emitting a key for a guessed table. The catalog match requires <b>exactly one</b> auto-increment
 * column on the resolved table; 0, &gt;1, table-not-found, or schema-mismatch all return empty.</p>
 *
 * <p>Identifier matching is case-insensitive for unquoted names (SQL's unquoted-identifier folding),
 * and the schema defaults to the deployment schema when the INSERT names a bare table.</p>
 */
public final class JdbcGeneratedKeyResolver {

    private JdbcGeneratedKeyResolver() {
    }

    /** A parsed table reference: an optional schema qualifier, the table name, and per-part quoting. */
    private record TableRef(String schema, boolean schemaQuoted, String table, boolean tableQuoted) {
    }

    /**
     * The single auto-increment/identity column of the {@code INSERT}'s target table, resolved from the
     * catalog, or empty when the table cannot be confidently scanned, is not in the model, or does not
     * have exactly one auto-increment column.
     *
     * @param schemaModel  the introspected catalog (may be {@code null} ⇒ empty: no catalog, reject)
     * @param insertSql    the constant INSERT text (the same text the emitter renders)
     * @param defaultSchema the deployment schema, used when the INSERT names a bare (unqualified) table
     */
    public static Optional<String> resolveAutoIncrementKeyColumn(
            SchemaModel schemaModel, String insertSql, String defaultSchema) {
        if (schemaModel == null || insertSql == null) {
            return Optional.empty();
        }
        Optional<TableRef> table = scanInsertTarget(insertSql);
        return table.flatMap(ref -> resolveSingleAutoIncrement(schemaModel, ref, defaultSchema));
    }

    // ---- catalog resolution -----------------------------------------------------------------

    private static Optional<String> resolveSingleAutoIncrement(
            SchemaModel schemaModel, TableRef ref, String defaultSchema) {
        SchemaModel.TableMeta matched = null;
        for (SchemaModel.TableMeta table : schemaModel.tables()) {
            if (tableMatches(table, ref, defaultSchema)) {
                if (matched != null) {
                    // Two catalog tables match the reference (e.g. same unquoted name in two schemas the
                    // reference does not disambiguate): ambiguous — reject rather than pick one.
                    return Optional.empty();
                }
                matched = table;
            }
        }
        if (matched == null) {
            return Optional.empty();
        }
        String autoIncrement = null;
        for (SchemaModel.ColumnMeta column : matched.columns()) {
            if (column.autoIncrement()) {
                if (autoIncrement != null) {
                    // >1 auto-increment column: composite/ambiguous generated key — reject.
                    return Optional.empty();
                }
                autoIncrement = column.name();
            }
        }
        return Optional.ofNullable(autoIncrement);
    }

    private static boolean tableMatches(SchemaModel.TableMeta table, TableRef ref, String defaultSchema) {
        if (!identifierEquals(table.name(), ref.table(), ref.tableQuoted())) {
            return false;
        }
        String referencedSchema = ref.schema() != null ? ref.schema() : defaultSchema;
        if (referencedSchema == null) {
            // No schema on the reference and no deployment default: match on table name alone.
            return true;
        }
        if (table.schema() == null) {
            return true;
        }
        // The reference's schema part is quoted only when ref.schemaQuoted(); a defaulted schema folds
        // case-insensitively (it is a transpile input, not user SQL text).
        boolean schemaQuoted = ref.schema() != null && ref.schemaQuoted();
        return identifierEquals(table.schema(), referencedSchema, schemaQuoted);
    }

    /**
     * Identifier equality: exact when the SQL reference was quoted (a quoted identifier is
     * case-sensitive), case-insensitive otherwise (SQL folds unquoted identifiers). The catalog name is
     * compared as stored.
     */
    private static boolean identifierEquals(String catalogName, String referenceName, boolean referenceQuoted) {
        if (catalogName == null || referenceName == null) {
            return false;
        }
        if (referenceQuoted) {
            return catalogName.equals(referenceName);
        }
        return catalogName.equalsIgnoreCase(referenceName);
    }

    // ---- parser-free INSERT-target scan (mirrors MySqlEmitter.injectIntoTargets' char scanner) ----

    /**
     * Scans a constant INSERT statement for its {@code INSERT [IGNORE] INTO <table>} target table,
     * skipping string literals, quoted/back-ticked identifiers and comments and tracking parenthesis
     * depth. Returns the parsed table reference, or empty when the text is not a plain
     * {@code INSERT INTO <table>} the scanner can read with confidence.
     */
    private static Optional<TableRef> scanInsertTarget(String sql) {
        int i = 0;
        int n = sql.length();

        i = skipTrivia(sql, i);
        // Must begin with INSERT (a leading WITH-CTE or any other statement is out of scope).
        if (!isKeywordAt(sql, i, "INSERT")) {
            return Optional.empty();
        }
        i += "INSERT".length();

        // Walk depth-0 tokens until the INTO keyword, allowing only the optional MySQL INSERT modifiers
        // (IGNORE / LOW_PRIORITY / HIGH_PRIORITY / DELAYED) between INSERT and INTO. Anything else means
        // a shape the scanner should not interpret — reject.
        while (true) {
            i = skipTrivia(sql, i);
            if (i >= n) {
                return Optional.empty();
            }
            if (isKeywordAt(sql, i, "INTO")) {
                i += "INTO".length();
                break;
            }
            String modifier = readBareWord(sql, i);
            if (modifier == null || !isInsertModifier(modifier)) {
                return Optional.empty();
            }
            i += modifier.length();
        }

        // The table name follows INTO. Parse an optional schema-qualified, optionally quoted/backticked
        // identifier: <part>[.<part>].
        i = skipTrivia(sql, i);
        Identifier first = readIdentifier(sql, i);
        if (first == null) {
            return Optional.empty();
        }
        i = first.end();

        int afterFirst = skipTrivia(sql, i);
        if (afterFirst < n && sql.charAt(afterFirst) == '.') {
            int afterDot = skipTrivia(sql, afterFirst + 1);
            Identifier second = readIdentifier(sql, afterDot);
            if (second == null) {
                return Optional.empty();
            }
            // schema.table — reject a three-part name (db.schema.table is outside the catalog model).
            int afterSecond = skipTrivia(sql, second.end());
            if (afterSecond < n && sql.charAt(afterSecond) == '.') {
                return Optional.empty();
            }
            if (!isTableNameBoundary(sql, second.end())) {
                return Optional.empty();
            }
            return Optional.of(new TableRef(first.text(), first.quoted(), second.text(), second.quoted()));
        }

        // Bare table name. The next non-trivia char must start the column list / VALUES / SELECT / SET /
        // end — i.e. a clean table-name boundary, not a stray identifier char (which would mean the scan
        // mis-read the name).
        if (!isTableNameBoundary(sql, first.end())) {
            return Optional.empty();
        }
        return Optional.of(new TableRef(null, false, first.text(), first.quoted()));
    }

    /** True when the INSERT modifier word between INSERT and INTO is one Titan tolerates. */
    private static boolean isInsertModifier(String word) {
        String upper = word.toUpperCase(Locale.ROOT);
        return upper.equals("IGNORE")
                || upper.equals("LOW_PRIORITY")
                || upper.equals("HIGH_PRIORITY")
                || upper.equals("DELAYED");
    }

    /**
     * Whether {@code end} is a valid end-of-table-name boundary: end of text, whitespace, a comment
     * start, or one of {@code ( . }. Anything else (e.g. a letter) means the identifier reader stopped
     * mid-token in a way that should not be trusted.
     */
    private static boolean isTableNameBoundary(String sql, int end) {
        if (end >= sql.length()) {
            return true;
        }
        char c = sql.charAt(end);
        if (Character.isWhitespace(c) || c == '(' || c == '.') {
            return true;
        }
        // A comment immediately after the name is also a clean boundary.
        char next = end + 1 < sql.length() ? sql.charAt(end + 1) : '\0';
        return (c == '-' && next == '-') || (c == '/' && next == '*');
    }

    /** A parsed identifier token: its unquoted text, whether it was quoted, and its end offset. */
    private record Identifier(String text, boolean quoted, int end) {
    }

    /**
     * Reads an identifier at {@code i}: a {@code "double-quoted"} or {@code `back-ticked`} delimited
     * identifier (with doubled-delimiter escapes), else a bare run of identifier characters. Returns
     * {@code null} when no identifier starts at {@code i}.
     */
    private static Identifier readIdentifier(String sql, int i) {
        if (i >= sql.length()) {
            return null;
        }
        char c = sql.charAt(i);
        if (c == '"' || c == '`') {
            return readDelimitedIdentifier(sql, i, c);
        }
        String bare = readBareWord(sql, i);
        if (bare == null) {
            return null;
        }
        return new Identifier(bare, false, i + bare.length());
    }

    private static Identifier readDelimitedIdentifier(String sql, int i, char delimiter) {
        StringBuilder text = new StringBuilder();
        int j = i + 1;
        int n = sql.length();
        while (j < n) {
            char c = sql.charAt(j);
            if (c == delimiter) {
                if (j + 1 < n && sql.charAt(j + 1) == delimiter) {
                    text.append(delimiter); // doubled delimiter is an escaped delimiter
                    j += 2;
                    continue;
                }
                return new Identifier(text.toString(), true, j + 1);
            }
            text.append(c);
            j++;
        }
        return null; // unterminated delimited identifier
    }

    /** A run of identifier characters starting at {@code i}, or {@code null} if none. */
    private static String readBareWord(String sql, int i) {
        int j = i;
        int n = sql.length();
        while (j < n && isIdentifierChar(sql.charAt(j))) {
            j++;
        }
        return j == i ? null : sql.substring(i, j);
    }

    /**
     * Advances past whitespace and {@code --} line / {@code /* *}{@code /} block comments starting at
     * {@code i}, returning the index of the next significant char.
     */
    private static int skipTrivia(String sql, int i) {
        int n = sql.length();
        while (i < n) {
            char c = sql.charAt(i);
            char next = i + 1 < n ? sql.charAt(i + 1) : '\0';
            if (Character.isWhitespace(c)) {
                i++;
            } else if (c == '-' && next == '-') {
                i += 2;
                while (i < n && sql.charAt(i) != '\n' && sql.charAt(i) != '\r') {
                    i++;
                }
            } else if (c == '/' && next == '*') {
                i += 2;
                while (i < n && !(sql.charAt(i) == '*' && i + 1 < n && sql.charAt(i + 1) == '/')) {
                    i++;
                }
                i = Math.min(n, i + 2);
            } else {
                break;
            }
        }
        return i;
    }

    /** True if {@code keyword} occurs at {@code index} as a whole word (not a prefix of an identifier). */
    private static boolean isKeywordAt(String sql, int index, String keyword) {
        if (!sql.regionMatches(true, index, keyword, 0, keyword.length())) {
            return false;
        }
        if (index > 0 && isIdentifierChar(sql.charAt(index - 1))) {
            return false;
        }
        int after = index + keyword.length();
        return after >= sql.length() || !isIdentifierChar(sql.charAt(after));
    }

    private static boolean isIdentifierChar(char c) {
        return c == '_' || c == '$' || Character.isLetterOrDigit(c);
    }

    /**
     * Exposed for tests: the parsed {@code [schema.]table} target of a constant INSERT (qualified name,
     * unquoted text joined by {@code .}), or empty when not a plain {@code INSERT INTO <table>}. The
     * resolver itself consumes the structured form; this projection is for assertions.
     */
    public static Optional<String> debugScanInsertTarget(String sql) {
        return scanInsertTarget(sql).map(ref ->
                ref.schema() == null ? ref.table() : ref.schema() + "." + ref.table());
    }
}
