package io.titan.runtime.jdbc;

import org.junit.jupiter.api.Test;
import titan.dsl.Column;
import titan.dsl.DSL;
import titan.dsl.Nullability;
import titan.dsl.SQLType;
import titan.dsl.SqlDialect;
import titan.dsl.Table;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JdbcExecutorTest {

    @Test
    void executesSelectAndMapsRows() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet resultSet = mock(ResultSet.class);

        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement("SELECT id FROM public.accounts")).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true, true, false);
        when(resultSet.getInt(1)).thenReturn(10, 20);

        JdbcExecutor executor = new JdbcExecutor(dataSource, SqlDialect.POSTGRESQL);

        List<Integer> ids = executor.fetchSql("SELECT id FROM public.accounts", rs -> rs.getInt(1));

        assertEquals(List.of(10, 20), ids);
        verify(connection).close();
        verify(statement).close();
        verify(resultSet).close();
    }

    @Test
    void fetchBindsSelectConditionValuesViaSetObject() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet resultSet = mock(ResultSet.class);

        TestAccountsTable accounts = new TestAccountsTable();
        String sql = "SELECT id FROM public.accounts WHERE email = ?";

        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(sql)).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true, false);
        when(resultSet.getInt(1)).thenReturn(10);

        JdbcExecutor executor = new JdbcExecutor(dataSource, SqlDialect.POSTGRESQL);
        List<Integer> ids = executor.fetch(
                DSL.select(accounts.ID).from(accounts).where(accounts.EMAIL.eq("a'); DROP TABLE accounts; --")),
                rs -> rs.getInt(1));

        assertEquals(List.of(10), ids);
        verify(connection).prepareStatement(sql);
        verify(statement).setObject(1, "a'); DROP TABLE accounts; --");
    }

    @Test
    void executesInsertBuilderWithBoundParameters() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);

        TestAccountsTable accounts = new TestAccountsTable();
        String sql = "INSERT INTO public.accounts (id) VALUES (?)";

        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(sql)).thenReturn(statement);
        when(statement.executeUpdate()).thenReturn(1);

        JdbcExecutor executor = new JdbcExecutor(dataSource, SqlDialect.POSTGRESQL);
        int updated = executor.execute(DSL.insertInto(accounts).set(accounts.ID, 7));

        assertEquals(1, updated);
        verify(connection).prepareStatement(sql);
        verify(statement).setObject(1, 7);
        verify(statement).executeUpdate();
    }

    @Test
    void createSniffsDialectOnceAndRendersMySqlUpsertSyntax() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        DatabaseMetaData metaData = mock(DatabaseMetaData.class);

        TestAccountsTable accounts = new TestAccountsTable();
        String sql = "INSERT INTO public.accounts (id) VALUES (?) ON DUPLICATE KEY UPDATE id = ?";

        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getMetaData()).thenReturn(metaData);
        when(metaData.getDatabaseProductName()).thenReturn("MySQL");
        when(connection.prepareStatement(sql)).thenReturn(statement);
        when(statement.executeUpdate()).thenReturn(1);

        JdbcExecutor executor = JdbcExecutor.create(dataSource);
        assertEquals(SqlDialect.MYSQL, executor.dialect());

        int updated = executor.execute(DSL.insertInto(accounts)
                .set(accounts.ID, 7)
                .onConflict(accounts.ID)
                .doUpdate()
                .set(accounts.ID, 8));

        assertEquals(1, updated);
        verify(connection).prepareStatement(sql);
        verify(statement).setObject(1, 7);
        verify(statement).setObject(2, 8);
        verify(statement).executeUpdate();
    }

    @Test
    void updateAndDeleteBindParametersViaSetObject() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement updateStatement = mock(PreparedStatement.class);
        PreparedStatement deleteStatement = mock(PreparedStatement.class);

        TestAccountsTable accounts = new TestAccountsTable();
        String updateSql = "UPDATE public.accounts SET email = ? WHERE id = ?";
        String deleteSql = "DELETE FROM public.accounts WHERE email = ?";

        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(updateSql)).thenReturn(updateStatement);
        when(connection.prepareStatement(deleteSql)).thenReturn(deleteStatement);
        when(updateStatement.executeUpdate()).thenReturn(1);
        when(deleteStatement.executeUpdate()).thenReturn(1);

        JdbcExecutor executor = new JdbcExecutor(dataSource, SqlDialect.POSTGRESQL);

        executor.execute(DSL.update(accounts).set(accounts.EMAIL, "x'y\\").where(accounts.ID.eq(42)));
        executor.execute(DSL.deleteFrom(accounts).where(accounts.EMAIL.eq("x'y\\")));

        verify(updateStatement).setObject(1, "x'y\\");
        verify(updateStatement).setObject(2, 42);
        verify(deleteStatement).setObject(1, "x'y\\");
    }

    @Test
    void executeReturningUsesGeneratedKeysOnMySqlInsteadOfSplicedSelect() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet generatedKeys = mock(ResultSet.class);

        TestAccountsTable accounts = new TestAccountsTable();
        String sql = "INSERT INTO public.accounts (email) VALUES (?)";

        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)).thenReturn(statement);
        when(statement.executeUpdate()).thenReturn(1);
        when(statement.getGeneratedKeys()).thenReturn(generatedKeys);
        when(generatedKeys.next()).thenReturn(true, false);
        when(generatedKeys.getLong(1)).thenReturn(99L);

        JdbcExecutor executor = new JdbcExecutor(dataSource, SqlDialect.MYSQL);
        List<Long> keys = executor.executeReturning(
                DSL.insertInto(accounts).set(accounts.EMAIL, "ada@example.com").returning(accounts.ID),
                rs -> rs.getLong(1));

        assertEquals(List.of(99L), keys);
        verify(connection).prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
        verify(statement).setObject(1, "ada@example.com");
    }

    @Test
    void wrapsSqlExceptionsWithSqlAccessorButKeepsSqlOutOfMessage() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        when(dataSource.getConnection()).thenThrow(new SQLException("boom"));

        JdbcExecutor executor = new JdbcExecutor(dataSource, SqlDialect.POSTGRESQL);
        JdbcExecutionException ex = assertThrows(JdbcExecutionException.class,
                () -> executor.executeSql("DELETE FROM public.accounts"));

        assertEquals("DELETE FROM public.accounts", ex.sql());
        assertFalse(ex.getMessage().contains("DELETE FROM public.accounts"),
                "SQL text must not leak into the exception message (audit R-7)");
    }

    @Test
    void builderExecutionFailuresReportTheRenderedSqlThatFailed() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);

        TestAccountsTable accounts = new TestAccountsTable();
        String sql = "INSERT INTO public.accounts (id) VALUES (?)";

        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(sql)).thenReturn(statement);
        when(statement.executeUpdate()).thenThrow(new SQLException("constraint violation"));

        JdbcExecutor executor = new JdbcExecutor(dataSource, SqlDialect.POSTGRESQL);
        JdbcExecutionException ex = assertThrows(JdbcExecutionException.class,
                () -> executor.execute(DSL.insertInto(accounts).set(accounts.ID, 7)));

        assertEquals(sql, ex.sql());
        assertFalse(ex.getMessage().contains(sql));
    }

    private static final class TestAccountsTable extends Table<Object> {
        private final Column<Integer> ID = column("id", SQLType.INTEGER, Nullability.NOT_NULL);
        private final Column<String> EMAIL = column("email", SQLType.VARCHAR, Nullability.NOT_NULL);

        private TestAccountsTable() {
            super("accounts", "public");
        }
    }
}
