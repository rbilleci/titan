package io.titan.gradle;

import io.titan.introspect.SqlStatementSplitter;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Splits packaged SQL scripts into individually executable statements for install verification.
 *
 * <p>Scripts using the MySQL-client {@code DELIMITER} convention (emitted MySQL routine bundles
 * and the MySQL runtime migration with its compound-bodied helpers) are split line-by-line
 * honoring the active delimiter; everything else goes through the shared comment- and
 * dollar-quote-aware {@link SqlStatementSplitter}.</p>
 */
final class TitanSqlScripts {

    private TitanSqlScripts() {
    }

    static List<String> split(String sqlScript) {
        if (!sqlScript.contains("DELIMITER ")) {
            return SqlStatementSplitter.split(sqlScript);
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
                String statement = SqlStatementSplitter.stripLeadingComments(
                        buffered.substring(0, buffered.length() - delimiter.length())).trim();
                if (!statement.isEmpty()) {
                    statements.add(statement);
                }
                current.setLength(0);
            }
        }

        String tail = SqlStatementSplitter.stripLeadingComments(current.toString()).trim();
        if (!tail.isEmpty()) {
            statements.add(tail);
        }
        return statements;
    }
}
