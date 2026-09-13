package io.titan.runtime.testing;

import io.titan.runtime.jdbc.JdbcExecutionException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.StringJoiner;

/**
 * SQL-mode test helper that executes generated database routines directly via CALL.
 */
public final class SqlModeRunner {
    private final Connection connection;

    SqlModeRunner(Connection connection) {
        this.connection = Objects.requireNonNull(connection, "connection");
    }

    public int call(String routineName, Object... args) {
        Objects.requireNonNull(routineName, "routineName");
        String sql = "CALL " + routineName + "(" + placeholders(args.length) + ")";
        try (CallableStatement statement = connection.prepareCall(sql)) {
            bindParameters(statement, args);
            return statement.executeUpdate();
        } catch (SQLException ex) {
            throw new JdbcExecutionException("Failed to execute routine call", ex, sql);
        }
    }

    public void deploySql(String sqlScript) {
        Objects.requireNonNull(sqlScript, "sqlScript");
        List<String> statements = splitStatements(sqlScript);
        try (Statement statement = connection.createStatement()) {
            for (String sql : statements) {
                statement.execute(sql);
            }
        } catch (SQLException ex) {
            throw new JdbcExecutionException("Failed to deploy SQL-mode script", ex, sqlScript);
        }
    }

    public void deploySql(Path sqlFile) {
        Objects.requireNonNull(sqlFile, "sqlFile");
        try {
            deploySql(Files.readString(sqlFile));
        } catch (IOException ex) {
            throw new JdbcExecutionException("Failed to read SQL-mode script file", ex, sqlFile.toString());
        }
    }

    public <T> List<T> fetchSql(String sql, SqlRowMapper<T> rowMapper) {
        Objects.requireNonNull(sql, "sql");
        Objects.requireNonNull(rowMapper, "rowMapper");
        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {
            List<T> rows = new ArrayList<>();
            while (resultSet.next()) {
                rows.add(rowMapper.map(resultSet));
            }
            return rows;
        } catch (SQLException ex) {
            throw new JdbcExecutionException("Failed to execute SQL-mode query", ex, sql);
        }
    }

    @FunctionalInterface
    public interface SqlRowMapper<T> {
        T map(ResultSet resultSet) throws SQLException;
    }

    private static String placeholders(int count) {
        if (count <= 0) {
            return "";
        }
        StringJoiner joiner = new StringJoiner(", ");
        for (int i = 0; i < count; i++) {
            joiner.add("?");
        }
        return joiner.toString();
    }

    private static void bindParameters(CallableStatement statement, Object[] args) throws SQLException {
        for (int i = 0; i < args.length; i++) {
            statement.setObject(i + 1, args[i]);
        }
    }

    private static List<String> splitStatements(String sqlScript) {
        List<String> statements = new ArrayList<>();
        String delimiter = ";";
        StringBuilder buffer = new StringBuilder();

        for (String line : sqlScript.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }

            if (trimmed.toUpperCase(Locale.ROOT).startsWith("DELIMITER ")) {
                String nextDelimiter = trimmed.substring("DELIMITER ".length()).trim();
                if (nextDelimiter.isEmpty()) {
                    throw new IllegalArgumentException("DELIMITER directive must declare a delimiter");
                }
                delimiter = nextDelimiter;
                continue;
            }

            if (buffer.length() > 0) {
                buffer.append('\n');
            }
            buffer.append(line);

            String current = buffer.toString().trim();
            if (current.endsWith(delimiter)) {
                String statement = current.substring(0, current.length() - delimiter.length()).trim();
                if (!statement.isEmpty()) {
                    statements.add(statement);
                }
                buffer.setLength(0);
            }
        }

        String tail = buffer.toString().trim();
        if (!tail.isEmpty()) {
            statements.add(tail);
        }

        return statements;
    }
}
