package io.titan.runtime.jdbc;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import titan.dsl.Column;
import titan.dsl.DSL;
import titan.dsl.Nullability;
import titan.dsl.SQLType;
import titan.dsl.SqlDialect;
import titan.dsl.Table;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the JdbcExecutor production features (audit R-6/R-7): execution listener
 * events (placeholder SQL only), query timeouts, batch execution, and transaction semantics.
 */
class JdbcExecutorProductionFeaturesTest {

    private record Event(String sql, Duration duration, TitanExecutionListener.Outcome outcome) {
    }

    private static final class RecordingListener implements TitanExecutionListener {
        final List<Event> events = new ArrayList<>();

        @Override
        public void onExecute(String sqlWithPlaceholders, Duration duration, Outcome outcome) {
            events.add(new Event(sqlWithPlaceholders, duration, outcome));
        }
    }

    @Test
    void listenerReceivesPlaceholderSqlDurationAndSuccessOutcome() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet resultSet = mock(ResultSet.class);

        TestAccountsTable accounts = new TestAccountsTable();
        String sql = "SELECT id FROM public.accounts WHERE email = ?";

        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(sql)).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(false);

        RecordingListener listener = new RecordingListener();
        JdbcExecutor executor = new JdbcExecutor(dataSource, SqlDialect.POSTGRESQL).withListener(listener);

        executor.fetch(DSL.select(accounts.ID).from(accounts).where(accounts.EMAIL.eq("secret@example.com")),
                rs -> rs.getInt(1));

        assertEquals(1, listener.events.size());
        Event event = listener.events.get(0);
        assertEquals(sql, event.sql());
        assertTrue(event.sql().contains("?"), "event must carry placeholder SQL");
        assertFalse(event.sql().contains("secret@example.com"),
                "bound values must never reach the listener (audit R-7)");
        assertFalse(event.duration().isNegative());
        assertEquals(TitanExecutionListener.Outcome.SUCCESS, event.outcome());
    }

    @Test
    void listenerReceivesFailureOutcomeAndListenerErrorsAreSuppressedOntoThePrimaryFailure() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);

        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement("DELETE FROM public.accounts")).thenReturn(statement);
        when(statement.executeUpdate()).thenThrow(new SQLException("constraint violation"));

        RecordingListener recording = new RecordingListener();
        IllegalStateException listenerBoom = new IllegalStateException("listener boom");
        TitanExecutionListener listener = (sql, duration, outcome) -> {
            recording.onExecute(sql, duration, outcome);
            throw listenerBoom;
        };
        JdbcExecutor executor = new JdbcExecutor(dataSource, SqlDialect.POSTGRESQL).withListener(listener);

        JdbcExecutionException failure = assertThrows(JdbcExecutionException.class,
                () -> executor.executeSql("DELETE FROM public.accounts"));

        assertEquals(TitanExecutionListener.Outcome.FAILURE, recording.events.get(0).outcome());
        assertEquals(1, failure.getSuppressed().length, "listener failure must not be swallowed");
        assertSame(listenerBoom, failure.getSuppressed()[0]);
    }

    @Test
    void listenerFailurePropagatesWhenTheStatementSucceeded() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);

        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement("UPDATE public.accounts SET id = 1")).thenReturn(statement);
        when(statement.executeUpdate()).thenReturn(1);

        IllegalStateException listenerBoom = new IllegalStateException("listener boom");
        JdbcExecutor executor = new JdbcExecutor(dataSource, SqlDialect.POSTGRESQL)
                .withListener((sql, duration, outcome) -> {
                    throw listenerBoom;
                });

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> executor.executeSql("UPDATE public.accounts SET id = 1"));
        assertSame(listenerBoom, thrown);
    }

    @Test
    void queryTimeoutIsAppliedToStatementsRoundedUpToWholeSeconds() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet resultSet = mock(ResultSet.class);

        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement("SELECT 1")).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(false);

        new JdbcExecutor(dataSource, SqlDialect.POSTGRESQL)
                .withQueryTimeout(Duration.ofMillis(1500))
                .fetchSql("SELECT 1", rs -> rs.getInt(1));

        verify(statement).setQueryTimeout(2);
    }

    @Test
    void zeroTimeoutMeansNoSetQueryTimeoutCall() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet resultSet = mock(ResultSet.class);

        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement("SELECT 1")).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(false);

        new JdbcExecutor(dataSource, SqlDialect.POSTGRESQL)
                .withQueryTimeout(Duration.ZERO)
                .fetchSql("SELECT 1", rs -> rs.getInt(1));

        verify(statement, never()).setQueryTimeout(0);
    }

    @Test
    void executeBatchBindsEveryRowIntoOnePreparedStatementBatch() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);

        TestAccountsTable accounts = new TestAccountsTable();
        String sql = "INSERT INTO public.accounts (id) VALUES (?)";

        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(sql)).thenReturn(statement);
        when(statement.executeBatch()).thenReturn(new int[]{1, 1});

        JdbcExecutor executor = new JdbcExecutor(dataSource, SqlDialect.POSTGRESQL);
        int[] counts = executor.executeBatch(List.of(
                DSL.insertInto(accounts).set(accounts.ID, 1),
                DSL.insertInto(accounts).set(accounts.ID, 2)));

        assertArrayEquals(new int[]{1, 1}, counts);
        InOrder order = inOrder(statement);
        order.verify(statement).setObject(1, 1);
        order.verify(statement).addBatch();
        order.verify(statement).setObject(1, 2);
        order.verify(statement).addBatch();
        order.verify(statement).executeBatch();
        verify(connection).prepareStatement(sql);
    }

    @Test
    void executeBatchRejectsHeterogeneousStatementShapesBeforeExecutingAnything() {
        DataSource dataSource = mock(DataSource.class);
        TestAccountsTable accounts = new TestAccountsTable();

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> new JdbcExecutor(dataSource, SqlDialect.POSTGRESQL).executeBatch(List.of(
                        DSL.insertInto(accounts).set(accounts.ID, 1),
                        DSL.insertInto(accounts).set(accounts.ID, 2).set(accounts.EMAIL, "x@example.com"))));

        assertTrue(ex.getMessage().contains("same table and column set"), ex.getMessage());
        verifyNoInteractions(dataSource);
    }

    @Test
    void emptyBatchIsANoOp() {
        DataSource dataSource = mock(DataSource.class);
        int[] counts = new JdbcExecutor(dataSource, SqlDialect.POSTGRESQL).executeBatch(List.of());
        assertEquals(0, counts.length);
        verifyNoInteractions(dataSource);
    }

    @Test
    void inTransactionCommitsOnSuccessAndRestoresAutoCommit() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);

        TestAccountsTable accounts = new TestAccountsTable();
        String sql = "INSERT INTO public.accounts (id) VALUES (?)";

        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getAutoCommit()).thenReturn(true);
        when(connection.prepareStatement(sql)).thenReturn(statement);
        when(statement.executeUpdate()).thenReturn(1);

        JdbcExecutor executor = new JdbcExecutor(dataSource, SqlDialect.POSTGRESQL);
        int updated = executor.inTransaction(tx -> tx.execute(DSL.insertInto(accounts).set(accounts.ID, 7)));

        assertEquals(1, updated);
        InOrder order = inOrder(connection, statement);
        order.verify(connection).setAutoCommit(false);
        order.verify(statement).executeUpdate();
        order.verify(connection).commit();
        order.verify(connection).setAutoCommit(true);
        order.verify(connection).close();
        verify(connection, never()).rollback();
    }

    @Test
    void inTransactionRollsBackAndRethrowsOnFailure() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);

        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getAutoCommit()).thenReturn(true);

        JdbcExecutor executor = new JdbcExecutor(dataSource, SqlDialect.POSTGRESQL);
        IllegalStateException boom = new IllegalStateException("boom");

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> executor.inTransaction(tx -> {
                    throw boom;
                }));

        assertSame(boom, thrown);
        InOrder order = inOrder(connection);
        order.verify(connection).setAutoCommit(false);
        order.verify(connection).rollback();
        order.verify(connection).setAutoCommit(true);
        order.verify(connection).close();
        verify(connection, never()).commit();
    }

    @Test
    void transactionalExecutorPinsItsConnectionAndCannotCloseIt() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet resultSet = mock(ResultSet.class);

        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getAutoCommit()).thenReturn(true);
        when(connection.prepareStatement("SELECT 1")).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(false);

        JdbcExecutor executor = new JdbcExecutor(dataSource, SqlDialect.POSTGRESQL);
        executor.inTransaction(tx -> {
            tx.fetchSql("SELECT 1", rs -> rs.getInt(1));
            tx.fetchSql("SELECT 1", rs -> rs.getInt(1));
            return null;
        });

        // Both statements ran on the single pinned connection; the inner try-with-resources
        // close() calls hit the non-closing proxy, the owner closes the real connection once.
        verify(dataSource).getConnection();
        verify(connection).close();
    }

    private static final class TestAccountsTable extends Table<Object> {
        private final Column<Integer> ID = column("id", SQLType.INTEGER, Nullability.NOT_NULL);
        private final Column<String> EMAIL = column("email", SQLType.VARCHAR, Nullability.NOT_NULL);

        private TestAccountsTable() {
            super("accounts", "public");
        }
    }
}
