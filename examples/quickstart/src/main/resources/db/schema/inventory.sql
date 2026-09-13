CREATE TABLE app.inventory (
    sku BIGINT NOT NULL PRIMARY KEY,
    available INTEGER NOT NULL
);

CREATE TABLE app.reservations (
    order_id BIGINT NOT NULL,
    sku BIGINT NOT NULL,
    quantity INTEGER NOT NULL,
    PRIMARY KEY (order_id, sku)
);
