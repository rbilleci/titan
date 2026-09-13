package io.titan.runtime.benchmark;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;
import org.testcontainers.containers.PostgreSQLContainer;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.TimeUnit;

/**
 * Benchmark suite for P3.5.2.
 *
 * Compares application-layer JDBC flow vs Titan-style generated routine flow
 * vs hand-written routine flow for key scenarios called out by the plan:
 * batch processing, aggregation, and CRUD lifecycle operations.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 2, time = 1)
@Measurement(iterations = 3, time = 1)
@Fork(1)
@State(Scope.Benchmark)
public class CrudAggregationBenchmark {

    private static final int ROW_COUNT = 1_000;

    private PostgreSQLContainer<?> postgres;
    private Connection connection;

    @Setup(Level.Trial)
    public void setupTrial() throws SQLException {
        postgres = new PostgreSQLContainer<>("postgres:16-alpine");
        postgres.start();

        connection = DriverManager.getConnection(
                postgres.getJdbcUrl(),
                postgres.getUsername(),
                postgres.getPassword()
        );
        connection.setAutoCommit(true);

        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS benchmark_orders (
                        id BIGSERIAL PRIMARY KEY,
                        account_id INT NOT NULL,
                        amount NUMERIC(12,2) NOT NULL,
                        status TEXT NOT NULL,
                        created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
                    )
                    """);

            statement.execute("""
                    CREATE OR REPLACE PROCEDURE titan_generated_bulk_insert_orders(p_rows INT)
                    LANGUAGE plpgsql
                    AS $$
                    BEGIN
                        INSERT INTO benchmark_orders(account_id, amount, status)
                        SELECT (g % 100), ((g % 1000) + 1)::NUMERIC(12,2), 'ACTIVE'
                        FROM generate_series(1, p_rows) AS g;
                    END;
                    $$
                    """);

            statement.execute("""
                    CREATE OR REPLACE PROCEDURE handwritten_bulk_insert_orders(p_rows INT)
                    LANGUAGE plpgsql
                    AS $$
                    DECLARE
                        i INT := 1;
                    BEGIN
                        WHILE i <= p_rows LOOP
                            INSERT INTO benchmark_orders(account_id, amount, status)
                            VALUES ((i % 100), ((i % 1000) + 1)::NUMERIC(12,2), 'ACTIVE');
                            i := i + 1;
                        END LOOP;
                    END;
                    $$
                    """);

            statement.execute("""
                    CREATE OR REPLACE FUNCTION titan_generated_sum_active_orders()
                    RETURNS NUMERIC(12,2)
                    LANGUAGE plpgsql
                    AS $$
                    BEGIN
                        RETURN COALESCE((
                            SELECT SUM(amount)
                            FROM benchmark_orders
                            WHERE status = 'ACTIVE'
                        ), 0)::NUMERIC(12,2);
                    END;
                    $$
                    """);

            statement.execute("""
                    CREATE OR REPLACE FUNCTION handwritten_sum_active_orders()
                    RETURNS NUMERIC(12,2)
                    LANGUAGE plpgsql
                    AS $$
                    DECLARE
                        total NUMERIC(12,2) := 0;
                        rec RECORD;
                    BEGIN
                        FOR rec IN
                            SELECT amount
                            FROM benchmark_orders
                            WHERE status = 'ACTIVE'
                        LOOP
                            total := total + rec.amount;
                        END LOOP;
                        RETURN total;
                    END;
                    $$
                    """);

            statement.execute("""
                    CREATE OR REPLACE PROCEDURE titan_generated_crud_cycle_orders(p_rows INT)
                    LANGUAGE plpgsql
                    AS $$
                    BEGIN
                        INSERT INTO benchmark_orders(account_id, amount, status)
                        SELECT (g % 100), ((g % 1000) + 1)::NUMERIC(12,2), 'ACTIVE'
                        FROM generate_series(1, p_rows) AS g;

                        UPDATE benchmark_orders
                        SET amount = amount + 1
                        WHERE status = 'ACTIVE'
                          AND MOD(account_id, 2) = 0;

                        DELETE FROM benchmark_orders
                        WHERE status = 'ACTIVE'
                          AND MOD(account_id, 10) = 0;
                    END;
                    $$
                    """);

            statement.execute("""
                    CREATE OR REPLACE PROCEDURE handwritten_crud_cycle_orders(p_rows INT)
                    LANGUAGE plpgsql
                    AS $$
                    DECLARE
                        i INT := 1;
                    BEGIN
                        WHILE i <= p_rows LOOP
                            INSERT INTO benchmark_orders(account_id, amount, status)
                            VALUES ((i % 100), ((i % 1000) + 1)::NUMERIC(12,2), 'ACTIVE');
                            i := i + 1;
                        END LOOP;

                        UPDATE benchmark_orders
                        SET amount = amount + 1
                        WHERE status = 'ACTIVE'
                          AND MOD(account_id, 2) = 0;

                        DELETE FROM benchmark_orders
                        WHERE status = 'ACTIVE'
                          AND MOD(account_id, 10) = 0;
                    END;
                    $$
                    """);
        }
    }

    @Setup(Level.Invocation)
    public void setupInvocation() throws SQLException {
        truncateOrders();
    }

    @TearDown(Level.Trial)
    public void tearDownTrial() throws SQLException {
        SQLException thrown = null;
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException ex) {
                thrown = ex;
            }
        }
        if (postgres != null) {
            postgres.stop();
        }
        if (thrown != null) {
            throw thrown;
        }
    }

    @Benchmark
    public int applicationLayerBatchInsert() throws SQLException {
        insertOrdersBatch(ROW_COUNT);
        return countOrders();
    }

    @Benchmark
    public int titanGeneratedProcedureBatchInsert() throws SQLException {
        callProcedure("CALL titan_generated_bulk_insert_orders(?)", ROW_COUNT);
        return countOrders();
    }

    @Benchmark
    public int handWrittenProcedureBatchInsert() throws SQLException {
        callProcedure("CALL handwritten_bulk_insert_orders(?)", ROW_COUNT);
        return countOrders();
    }

    @Benchmark
    public BigDecimal applicationLayerAggregation() throws SQLException {
        insertOrdersBatch(ROW_COUNT);
        return selectActiveAmountSum();
    }

    @Benchmark
    public BigDecimal titanGeneratedProcedureAggregation() throws SQLException {
        insertOrdersBatch(ROW_COUNT);
        return selectScalar("SELECT titan_generated_sum_active_orders()");
    }

    @Benchmark
    public BigDecimal handWrittenProcedureAggregation() throws SQLException {
        insertOrdersBatch(ROW_COUNT);
        return selectScalar("SELECT handwritten_sum_active_orders()");
    }

    @Benchmark
    public BigDecimal applicationLayerCrudCycle() throws SQLException {
        insertOrdersBatch(ROW_COUNT);
        runCrudMutations();
        return selectActiveAmountSum();
    }

    @Benchmark
    public BigDecimal titanGeneratedProcedureCrudCycle() throws SQLException {
        callProcedure("CALL titan_generated_crud_cycle_orders(?)", ROW_COUNT);
        return selectActiveAmountSum();
    }

    @Benchmark
    public BigDecimal handWrittenProcedureCrudCycle() throws SQLException {
        callProcedure("CALL handwritten_crud_cycle_orders(?)", ROW_COUNT);
        return selectActiveAmountSum();
    }

    private void truncateOrders() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("TRUNCATE TABLE benchmark_orders");
        }
    }

    private void insertOrdersBatch(int rows) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO benchmark_orders(account_id, amount, status) VALUES (?, ?, ?)"
        )) {
            for (int i = 0; i < rows; i++) {
                insert.setInt(1, i % 100);
                insert.setBigDecimal(2, BigDecimal.valueOf((i % 1000) + 1L));
                insert.setString(3, "ACTIVE");
                insert.addBatch();
            }
            insert.executeBatch();
        }
    }

    private void runCrudMutations() throws SQLException {
        try (PreparedStatement update = connection.prepareStatement("""
                UPDATE benchmark_orders
                SET amount = amount + 1
                WHERE status = 'ACTIVE'
                  AND MOD(account_id, 2) = 0
                """)) {
            update.executeUpdate();
        }

        try (PreparedStatement delete = connection.prepareStatement("""
                DELETE FROM benchmark_orders
                WHERE status = 'ACTIVE'
                  AND MOD(account_id, 10) = 0
                """)) {
            delete.executeUpdate();
        }
    }

    private void callProcedure(String sql, int rows) throws SQLException {
        try (PreparedStatement call = connection.prepareStatement(sql)) {
            call.setInt(1, rows);
            call.execute();
        }
    }

    private int countOrders() throws SQLException {
        try (PreparedStatement count = connection.prepareStatement("SELECT COUNT(*) FROM benchmark_orders");
             ResultSet rs = count.executeQuery()) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private BigDecimal selectActiveAmountSum() throws SQLException {
        return selectScalar("SELECT COALESCE(SUM(amount), 0) FROM benchmark_orders WHERE status = 'ACTIVE'");
    }

    private BigDecimal selectScalar(String sql) throws SQLException {
        try (PreparedStatement aggregate = connection.prepareStatement(sql);
             ResultSet rs = aggregate.executeQuery()) {
            rs.next();
            return rs.getBigDecimal(1);
        }
    }
}
