package example;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import titan.dsl.StoredFunction;
import titan.dsl.StoredProcedure;

public final class AccountRoutines {
    private AccountRoutines() {}

    @StoredProcedure
    public static void creditAccount(Connection connection, long accountId, BigDecimal amount)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE app.accounts SET balance = balance + ? WHERE id = ?")) {
            statement.setBigDecimal(1, amount);
            statement.setLong(2, accountId);
            statement.executeUpdate();
        }
    }

    @StoredFunction
    public static int addPoints(int current, int additional) {
        return current + additional;
    }
}
