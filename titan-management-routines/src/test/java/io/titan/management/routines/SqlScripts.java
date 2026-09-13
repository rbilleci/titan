package io.titan.management.routines;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Test helper: splits emitted SQL scripts into individually executable statements (mirrors core's
 * test splitter). Scripts using the MySQL-client {@code DELIMITER} convention are split line-by-line
 * honoring the active delimiter; everything else is split on semicolons with single-quote/
 * double-quote/dollar-quote awareness (PostgreSQL function bodies).
 */
final class SqlScripts {

    private SqlScripts() {
    }

    static List<String> split(String sqlScript) {
        if (!sqlScript.contains("DELIMITER ")) {
            return splitSemicolonStatements(sqlScript);
        }

        List<String> statements = new ArrayList<>();
        String delimiter = ";";
        StringBuilder current = new StringBuilder();

        for (String line : sqlScript.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.toUpperCase(Locale.ROOT).startsWith("DELIMITER ")) {
                delimiter = trimmed.substring("DELIMITER ".length()).trim();
                continue;
            }
            if (current.length() > 0) {
                current.append('\n');
            }
            current.append(line);
            String buffered = current.toString().trim();
            if (buffered.endsWith(delimiter)) {
                String statement = buffered.substring(0, buffered.length() - delimiter.length()).trim();
                if (!statement.isEmpty()) {
                    statements.add(statement);
                }
                current.setLength(0);
            }
        }

        String tail = current.toString().trim();
        if (!tail.isEmpty()) {
            statements.add(tail);
        }
        return statements;
    }

    private static List<String> splitSemicolonStatements(String rawScript) {
        // Strip `--`-to-end-of-line comments OUTSIDE string literals first. Hand-authored DDL
        // carries comment prose that can contain stray `);` / `;` which would otherwise mis-split
        // the script (and leading comment lines are not valid statements on MySQL).
        String sqlScript = stripLineComments(rawScript);
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inSingleQuote = false;
        boolean inDoubleQuote = false;
        String dollarTag = null;

        for (int i = 0; i < sqlScript.length(); i++) {
            if (dollarTag != null) {
                if (sqlScript.startsWith(dollarTag, i)) {
                    current.append(dollarTag);
                    i += dollarTag.length() - 1;
                    dollarTag = null;
                } else {
                    current.append(sqlScript.charAt(i));
                }
                continue;
            }

            char c = sqlScript.charAt(i);
            if (inSingleQuote) {
                current.append(c);
                if (c == '\'' && (i == 0 || sqlScript.charAt(i - 1) != '\\')) {
                    inSingleQuote = false;
                }
                continue;
            }
            if (inDoubleQuote) {
                current.append(c);
                if (c == '"' && (i == 0 || sqlScript.charAt(i - 1) != '\\')) {
                    inDoubleQuote = false;
                }
                continue;
            }
            if (c == '\'') {
                inSingleQuote = true;
                current.append(c);
                continue;
            }
            if (c == '"') {
                inDoubleQuote = true;
                current.append(c);
                continue;
            }
            if (c == '$') {
                int end = sqlScript.indexOf('$', i + 1);
                if (end > i) {
                    String candidate = sqlScript.substring(i, end + 1);
                    if (candidate.matches("\\$[A-Za-z0-9_]*\\$")) {
                        dollarTag = candidate;
                        current.append(candidate);
                        i = end;
                        continue;
                    }
                }
            }
            if (c == ';') {
                String statement = current.toString().trim();
                if (!statement.isEmpty()) {
                    statements.add(statement);
                }
                current.setLength(0);
                continue;
            }
            current.append(c);
        }

        String tail = current.toString().trim();
        if (!tail.isEmpty()) {
            statements.add(tail);
        }
        return statements;
    }

    private static String stripLineComments(String sqlScript) {
        StringBuilder out = new StringBuilder(sqlScript.length());
        boolean inSingleQuote = false;
        boolean inDoubleQuote = false;
        for (int i = 0; i < sqlScript.length(); i++) {
            char c = sqlScript.charAt(i);
            if (!inSingleQuote && !inDoubleQuote && c == '-' && i + 1 < sqlScript.length()
                    && sqlScript.charAt(i + 1) == '-') {
                // Skip to end of line.
                while (i < sqlScript.length() && sqlScript.charAt(i) != '\n') {
                    i++;
                }
                if (i < sqlScript.length()) {
                    out.append('\n');
                }
                continue;
            }
            if (c == '\'' && !inDoubleQuote && (i == 0 || sqlScript.charAt(i - 1) != '\\')) {
                inSingleQuote = !inSingleQuote;
            } else if (c == '"' && !inSingleQuote && (i == 0 || sqlScript.charAt(i - 1) != '\\')) {
                inDoubleQuote = !inDoubleQuote;
            }
            out.append(c);
        }
        return out.toString();
    }
}
