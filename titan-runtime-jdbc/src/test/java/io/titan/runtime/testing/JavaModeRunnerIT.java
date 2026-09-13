package io.titan.runtime.testing;

import org.junit.jupiter.api.Test;
import titan.dsl.DSL;
import titan.dsl.Nullability;
import titan.dsl.SQLType;
import titan.dsl.Table;
import titan.dsl.Column;

import java.sql.Connection;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;

@TitanTest(targets = {DatabaseTarget.POSTGRESQL, DatabaseTarget.MYSQL})
// Docker-dependent (Testcontainers); runs via the integrationTest task, excluded from plain test (plan 4.5).
@org.junit.jupiter.api.Tag("docker")
class JavaModeRunnerIT {

    @Test
    void executesDslSqlViaJdbcForEachTarget(TitanTestContext context) throws Exception {
        for (DatabaseTarget target : DatabaseTarget.values()) {
            Connection connection = context.connection(target);
            createAccountsTable(connection);

            TestAccountsTable accounts = new TestAccountsTable();
            int inserted = context.javaMode(target)
                    .run(jdbc -> jdbc.execute(DSL.insertInto(accounts)
                            .set(accounts.ID, 7)
                            .set(accounts.EMAIL, "alice@example.com")));

            int count = context.javaMode(target)
                    .run(jdbc -> jdbc.fetchSql("SELECT COUNT(*) FROM accounts", rs -> rs.getInt(1)).get(0));

            assertEquals(1, inserted);
            assertEquals(1, count);
        }
    }

    private void createAccountsTable(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS accounts");
            statement.execute("CREATE TABLE accounts(id INT PRIMARY KEY, email VARCHAR(255) NOT NULL)");
        }
    }

    private static final class TestAccountsTable extends Table<Object> {
        private final Column<Integer> ID = column("id", SQLType.INTEGER, Nullability.NOT_NULL);
        private final Column<String> EMAIL = column("email", SQLType.VARCHAR, Nullability.NOT_NULL);

        private TestAccountsTable() {
            super("accounts", "");
        }
    }
}
