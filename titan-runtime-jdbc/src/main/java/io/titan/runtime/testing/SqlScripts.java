package io.titan.runtime.testing;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Statement splitting for harness-applied SQL scripts: semicolon-separated scripts with
 * single/double-quote and PostgreSQL dollar-quote awareness, plus the MySQL-client
 * {@code DELIMITER} convention used by the emitted MySQL routine bundles.
 */
final class SqlScripts {

    private SqlScripts() {
    }

    static List<String> splitStatements(String sqlScript) {
        if (sqlScript.contains("DELIMITER ")) {
            return splitDelimiterStatements(sqlScript);
        }
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inSingleQuote = false;
        boolean inDoubleQuote = false;
        String dollarTag = null;

        for (int i = 0; i < sqlScript.length(); i++) {
            char c = sqlScript.charAt(i);
            if (dollarTag != null) {
                current.append(c);
                if (sqlScript.startsWith(dollarTag, i)) {
                    current.append(sqlScript, i + 1, i + dollarTag.length());
                    i += dollarTag.length() - 1;
                    dollarTag = null;
                }
                continue;
            }

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

    /**
     * Line-based splitting for scripts using the MySQL-client {@code DELIMITER} convention
     * (compound-bodied runtime functions contain {@code ;} inside {@code BEGIN ... END$$}
     * blocks). Same convention as the emitted MySQL routine bundles.
     */
    private static List<String> splitDelimiterStatements(String sqlScript) {
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
}
