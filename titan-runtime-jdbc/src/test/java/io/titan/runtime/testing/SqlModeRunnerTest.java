package io.titan.runtime.testing;

import io.titan.runtime.jdbc.JdbcExecutionException;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SqlModeRunnerTest {

    @Test
    void deploysSqlScriptFromFile() throws Exception {
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        when(connection.createStatement()).thenReturn(statement);

        SqlModeRunner runner = new SqlModeRunner(connection);
        Path script = Files.createTempFile("sql-mode-runner", ".sql");
        Files.writeString(script, "CREATE TABLE t(id INT);\nINSERT INTO t(id) VALUES (1);");

        try {
            runner.deploySql(script);
        } finally {
            Files.deleteIfExists(script);
        }

        verify(statement).execute("CREATE TABLE t(id INT)");
        verify(statement).execute("INSERT INTO t(id) VALUES (1)");
    }

    @Test
    void throwsJdbcExecutionExceptionWhenSqlFileDoesNotExist() {
        Connection connection = mock(Connection.class);
        SqlModeRunner runner = new SqlModeRunner(connection);

        assertThrows(JdbcExecutionException.class,
                () -> runner.deploySql(Path.of("/tmp/does-not-exist-sql-mode-runner.sql")));
    }
}
