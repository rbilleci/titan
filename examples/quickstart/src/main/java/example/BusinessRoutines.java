package example;

import static generated.catalog.app.tables.Inventory.INVENTORY;
import static generated.catalog.app.tables.Reservations.RESERVATIONS;
import static titan.dsl.DSL.insertInto;
import static titan.dsl.DSL.select;
import static titan.dsl.DSL.update;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import titan.dsl.StoredFunction;
import titan.dsl.StoredProcedure;

public final class BusinessRoutines {
    private BusinessRoutines() {}

    @StoredProcedure
    public static void reserveStock(long orderId, long sku, int quantity) throws SQLException {
        if (quantity <= 0) {
            throw new SQLException("quantity must be positive");
        }
        Integer available = select(INVENTORY.AVAILABLE)
                .from(INVENTORY)
                .where(INVENTORY.SKU.eq(sku))
                .forUpdate()
                .fetchScalar();
        if (available == null) {
            throw new SQLException("unknown SKU");
        }
        if (available < quantity) {
            throw new SQLException("insufficient stock");
        }
        update(INVENTORY)
                .set(INVENTORY.AVAILABLE, INVENTORY.AVAILABLE.subtract(quantity))
                .where(INVENTORY.SKU.eq(sku))
                .execute();
        insertInto(RESERVATIONS)
                .set(RESERVATIONS.ORDER_ID, orderId)
                .set(RESERVATIONS.SKU, sku)
                .set(RESERVATIONS.QUANTITY, quantity)
                .execute();
    }

    @StoredProcedure
    public static void reserveStockJdbc(Connection connection, long orderId, long sku, int quantity)
            throws SQLException {
        if (quantity <= 0) {
            throw new SQLException("quantity must be positive");
        }
        int available;
        try (PreparedStatement read = connection.prepareStatement(
                "SELECT available FROM app.inventory WHERE sku = ? FOR UPDATE")) {
            read.setLong(1, sku);
            try (ResultSet rows = read.executeQuery()) {
                if (!rows.next()) {
                    throw new SQLException("unknown SKU");
                }
                available = rows.getInt("available");
            }
        }
        if (available < quantity) {
            throw new SQLException("insufficient stock");
        }
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE app.inventory SET available = available - ? WHERE sku = ?")) {
            update.setInt(1, quantity);
            update.setLong(2, sku);
            update.executeUpdate();
        }
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO app.reservations (order_id, sku, quantity) VALUES (?, ?, ?)")) {
            insert.setLong(1, orderId);
            insert.setLong(2, sku);
            insert.setInt(3, quantity);
            insert.executeUpdate();
        }
    }

    @StoredFunction
    public static int monthlyChargeCents(int units, boolean partner) {
        if (units < 0 || units > 1000000) {
            throw new IllegalArgumentException("units must be between 0 and 1000000");
        }
        int remaining = units;
        int cents = 0;
        if (remaining > 1000) {
            cents += (remaining - 1000) * 2;
            remaining = 1000;
        }
        if (remaining > 100) {
            cents += (remaining - 100) * 3;
            remaining = 100;
        }
        cents += remaining * 5;
        if (partner) {
            cents = partnerPrice(cents);
        }
        if (units > 0 && cents < 200) {
            return 200;
        }
        return cents;
    }

    private static int partnerPrice(int cents) {
        return cents * 9 / 10;
    }
}
