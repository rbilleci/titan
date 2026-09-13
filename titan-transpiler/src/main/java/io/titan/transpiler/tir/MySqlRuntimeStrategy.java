package io.titan.transpiler.tir;

/**
 * MySQL runtime helper deployment.
 *
 * <p>Arithmetic emulation helpers (plan 2.4, audit E-7/E-11), wired by
 * {@link EmulationInsertionPass} via {@link MySqlEmitter}:
 * <ul>
 *   <li>{@code titan_rt_java_int_div}/{@code titan_rt_java_mod}: Java integer division/remainder
 *       parity. MySQL {@code DIV} truncates toward zero and {@code MOD} takes the dividend's
 *       sign — both match Java — but native {@code /} yields DECIMAL and a zero divisor yields
 *       NULL even under the default strict {@code sql_mode}, so the helpers guard with an
 *       explicit {@code SIGNAL SQLSTATE '22012'} (PostgreSQL's division_by_zero), preferring
 *       explicit SQL over session state per the E-1/E-8 precedent. These two are compound-bodied
 *       and therefore wrapped in a {@code DELIMITER $$} block, the same script convention the
 *       emitted routine bundles already use.</li>
 *   <li>{@code titan_rt_java_int_add/sub/mul}: 32-bit Java wraparound via 64-bit arithmetic and
 *       the {@code ((x + 2^31) mod 2^32) - 2^31} double-mod reduction (the inner {@code %} takes
 *       the dividend's sign in MySQL, so the {@code + 2^32, % 2^32} second step normalizes
 *       negative residues — correct for negative operands and the extremes, e.g.
 *       {@code MAX_VALUE+1 = MIN_VALUE}, {@code MIN_VALUE*MIN_VALUE = 0}). INT×INT products are
 *       computed with BIGINT precision in MySQL, so the intermediate never overflows.</li>
 * </ul>
 */
final class MySqlRuntimeStrategy implements RuntimeStrategy {
    @Override
    public String runtimeNamespace() {
        return "titan_rt";
    }

    @Override
    public boolean supportsNativeArrays() {
        return false;
    }

    @Override
    public java.util.List<SqlObject> runtimeObjects() {
        // The titan_rt_* helpers are created unqualified — they land in the connection's
        // default database; the telemetry table alone lives in the fixed titan_runtime schema.
        return java.util.List.of(
                SqlObject.of(SqlObject.Kind.TABLE, "titan_runtime", "telemetry"),
                fn("titan_rt_java_mod", "BIGINT", p("a", "BIGINT"), p("b", "BIGINT")),
                fn("titan_rt_java_int_div", "BIGINT", p("a", "BIGINT"), p("b", "BIGINT")),
                fn("titan_rt_java_int_add", "INT", p("a", "INT"), p("b", "INT")),
                fn("titan_rt_java_int_sub", "INT", p("a", "INT"), p("b", "INT")),
                fn("titan_rt_java_int_mul", "INT", p("a", "INT"), p("b", "INT")),
                fn("titan_rt_java_round", "DECIMAL(65,30)", p("v", "DECIMAL(65,30)"), p("scale_value", "INT")),
                fn("titan_rt_list_add", "JSON", p("list_value", "JSON"), p("element_value", "TEXT")),
                fn("titan_rt_list_get", "TEXT", p("list_value", "JSON"), p("index_value", "INT")),
                fn("titan_rt_list_size", "INT", p("list_value", "JSON")),
                fn("titan_rt_list_remove", "JSON", p("list_value", "JSON"), p("index_value", "INT")),
                fn("titan_rt_list_contains", "TINYINT(1)", p("list_value", "JSON"), p("element_value", "TEXT")),
                proc("titan_rt_list_temp_init"),
                proc("titan_rt_list_temp_add", p("list_key", "VARCHAR(128)"), p("element_value", "TEXT")),
                fn("titan_rt_list_temp_get", "TEXT", p("list_key", "VARCHAR(128)"), p("index_value", "INT")),
                fn("titan_rt_list_temp_size", "INT", p("list_key", "VARCHAR(128)")),
                fn("titan_rt_list_temp_contains", "TINYINT(1)", p("list_key", "VARCHAR(128)"), p("element_value", "TEXT")),
                proc("titan_rt_list_temp_remove", p("list_key", "VARCHAR(128)"), p("index_value", "INT")),
                proc("titan_rt_list_temp_cleanup"),
                fn("titan_rt_map_put", "JSON", p("map_value", "JSON"), p("key_value", "TEXT"), p("element_value", "JSON")),
                fn("titan_rt_map_get", "JSON", p("map_value", "JSON"), p("key_value", "TEXT")),
                fn("titan_rt_map_contains_key", "TINYINT(1)", p("map_value", "JSON"), p("key_value", "TEXT")),
                fn("titan_rt_map_remove", "JSON", p("map_value", "JSON"), p("key_value", "TEXT")),
                fn("titan_rt_map_keys", "JSON", p("map_value", "JSON")),
                fn("titan_rt_map_size", "INT", p("map_value", "JSON")),
                fn("titan_rt_set_add", "JSON", p("set_value", "JSON"), p("element_value", "TEXT")),
                fn("titan_rt_set_contains", "TINYINT(1)", p("set_value", "JSON"), p("element_value", "TEXT")),
                fn("titan_rt_set_remove", "JSON", p("set_value", "JSON"), p("element_value", "TEXT")),
                fn("titan_rt_set_size", "INT", p("set_value", "JSON")),
                proc("titan_rt_set_temp_init"),
                proc("titan_rt_set_temp_add", p("set_key", "VARCHAR(128)"), p("element_value", "TEXT")),
                fn("titan_rt_set_temp_contains", "TINYINT(1)", p("set_key", "VARCHAR(128)"), p("element_value", "TEXT")),
                proc("titan_rt_set_temp_remove", p("set_key", "VARCHAR(128)"), p("element_value", "TEXT")),
                fn("titan_rt_set_temp_size", "INT", p("set_key", "VARCHAR(128)")),
                proc("titan_rt_set_temp_cleanup"),
                fn("titan_rt_static_get", "TEXT", p("var_name", "TEXT")),
                fn("titan_rt_static_set", "TEXT", p("var_name", "TEXT"), p("var_value", "TEXT")),
                fn("titan_rt_static_reset", "JSON", p("var_name", "TEXT")));
    }

    private static SqlObject fn(String name, String returnType, SqlObject.Parameter... parameters) {
        return new SqlObject(SqlObject.Kind.FUNCTION, "", name, java.util.List.of(parameters), returnType, "");
    }

    private static SqlObject proc(String name, SqlObject.Parameter... parameters) {
        return new SqlObject(SqlObject.Kind.PROCEDURE, "", name, java.util.List.of(parameters), "", "");
    }

    private static SqlObject.Parameter p(String name, String type) {
        return new SqlObject.Parameter(name, type);
    }

    @Override
    public String runtimeMigrationSql() {
        return """
                CREATE TABLE IF NOT EXISTS titan_runtime.telemetry (
                  id BIGINT AUTO_INCREMENT PRIMARY KEY,
                  procedure_name TEXT NOT NULL,
                  started_at TIMESTAMP(6) NOT NULL,
                  finished_at TIMESTAMP(6) NULL,
                  duration_ms DOUBLE,
                  rows_affected BIGINT,
                  status VARCHAR(32) NOT NULL DEFAULT 'running',
                  error_sqlstate CHAR(5),
                  error_message TEXT,
                  parameters JSON
                );

                DELIMITER $$

                DROP FUNCTION IF EXISTS titan_rt_java_mod$$
                CREATE FUNCTION titan_rt_java_mod(a BIGINT, b BIGINT)
                RETURNS BIGINT
                DETERMINISTIC
                BEGIN
                  IF b = 0 THEN
                    SIGNAL SQLSTATE '22012' SET MESSAGE_TEXT = 'division by zero';
                  END IF;
                  RETURN a MOD b;
                END$$

                DROP FUNCTION IF EXISTS titan_rt_java_int_div$$
                CREATE FUNCTION titan_rt_java_int_div(a BIGINT, b BIGINT)
                RETURNS BIGINT
                DETERMINISTIC
                BEGIN
                  IF b = 0 THEN
                    SIGNAL SQLSTATE '22012' SET MESSAGE_TEXT = 'division by zero';
                  END IF;
                  RETURN a DIV b;
                END$$

                DELIMITER ;

                DROP FUNCTION IF EXISTS titan_rt_java_int_add;
                CREATE FUNCTION titan_rt_java_int_add(a INT, b INT)
                RETURNS INT
                DETERMINISTIC
                RETURN CAST((((((CAST(a AS SIGNED) + CAST(b AS SIGNED)) + 2147483648) % 4294967296) + 4294967296) % 4294967296) - 2147483648 AS SIGNED);

                DROP FUNCTION IF EXISTS titan_rt_java_int_sub;
                CREATE FUNCTION titan_rt_java_int_sub(a INT, b INT)
                RETURNS INT
                DETERMINISTIC
                RETURN CAST((((((CAST(a AS SIGNED) - CAST(b AS SIGNED)) + 2147483648) % 4294967296) + 4294967296) % 4294967296) - 2147483648 AS SIGNED);

                DROP FUNCTION IF EXISTS titan_rt_java_int_mul;
                CREATE FUNCTION titan_rt_java_int_mul(a INT, b INT)
                RETURNS INT
                DETERMINISTIC
                RETURN CAST((((((CAST(a AS SIGNED) * CAST(b AS SIGNED)) + 2147483648) % 4294967296) + 4294967296) % 4294967296) - 2147483648 AS SIGNED);

                DROP FUNCTION IF EXISTS titan_rt_java_round;
                CREATE FUNCTION titan_rt_java_round(v DECIMAL(65,30), scale_value INT)
                RETURNS DECIMAL(65,30)
                DETERMINISTIC
                RETURN CASE
                  WHEN ABS(v * POW(10, scale_value) - TRUNCATE(v * POW(10, scale_value), 0)) = 0.5
                       AND MOD(ABS(TRUNCATE(v * POW(10, scale_value), 0)), 2) = 0
                    THEN TRUNCATE(v * POW(10, scale_value), 0) / POW(10, scale_value)
                  ELSE ROUND(v, scale_value)
                END;

                DROP FUNCTION IF EXISTS titan_rt_list_add;
                CREATE FUNCTION titan_rt_list_add(list_value JSON, element_value TEXT)
                RETURNS JSON
                DETERMINISTIC
                RETURN JSON_ARRAY_APPEND(COALESCE(list_value, JSON_ARRAY()), '$', element_value);

                DROP FUNCTION IF EXISTS titan_rt_list_get;
                CREATE FUNCTION titan_rt_list_get(list_value JSON, index_value INT)
                RETURNS TEXT
                DETERMINISTIC
                RETURN JSON_UNQUOTE(JSON_EXTRACT(COALESCE(list_value, JSON_ARRAY()), CONCAT('$[', index_value, ']')));

                DROP FUNCTION IF EXISTS titan_rt_list_size;
                CREATE FUNCTION titan_rt_list_size(list_value JSON)
                RETURNS INT
                DETERMINISTIC
                RETURN JSON_LENGTH(COALESCE(list_value, JSON_ARRAY()));

                DROP FUNCTION IF EXISTS titan_rt_list_remove;
                CREATE FUNCTION titan_rt_list_remove(list_value JSON, index_value INT)
                RETURNS JSON
                DETERMINISTIC
                RETURN JSON_REMOVE(COALESCE(list_value, JSON_ARRAY()), CONCAT('$[', index_value, ']'));

                DROP FUNCTION IF EXISTS titan_rt_list_contains;
                CREATE FUNCTION titan_rt_list_contains(list_value JSON, element_value TEXT)
                RETURNS TINYINT(1)
                DETERMINISTIC
                RETURN JSON_CONTAINS(COALESCE(list_value, JSON_ARRAY()), JSON_QUOTE(element_value), '$');

                DROP PROCEDURE IF EXISTS titan_rt_list_temp_init;
                CREATE PROCEDURE titan_rt_list_temp_init()
                CREATE TEMPORARY TABLE IF NOT EXISTS titan_rt_list_tmp (
                  list_id VARCHAR(128) NOT NULL,
                  item_index INT NOT NULL,
                  item_value TEXT,
                  PRIMARY KEY (list_id, item_index)
                );

                DROP PROCEDURE IF EXISTS titan_rt_list_temp_add;
                CREATE PROCEDURE titan_rt_list_temp_add(list_key VARCHAR(128), element_value TEXT)
                INSERT INTO titan_rt_list_tmp(list_id, item_index, item_value)
                SELECT list_key, COALESCE(MAX(item_index), -1) + 1, element_value
                FROM titan_rt_list_tmp
                WHERE list_id = list_key;

                DROP FUNCTION IF EXISTS titan_rt_list_temp_get;
                CREATE FUNCTION titan_rt_list_temp_get(list_key VARCHAR(128), index_value INT)
                RETURNS TEXT
                READS SQL DATA
                RETURN (
                  SELECT item_value
                  FROM titan_rt_list_tmp
                  WHERE list_id = list_key AND item_index = index_value
                  LIMIT 1
                );

                DROP FUNCTION IF EXISTS titan_rt_list_temp_size;
                CREATE FUNCTION titan_rt_list_temp_size(list_key VARCHAR(128))
                RETURNS INT
                READS SQL DATA
                RETURN (
                  SELECT COUNT(*)
                  FROM titan_rt_list_tmp
                  WHERE list_id = list_key
                );

                DROP FUNCTION IF EXISTS titan_rt_list_temp_contains;
                CREATE FUNCTION titan_rt_list_temp_contains(list_key VARCHAR(128), element_value TEXT)
                RETURNS TINYINT(1)
                READS SQL DATA
                RETURN EXISTS(
                  SELECT 1
                  FROM titan_rt_list_tmp
                  WHERE list_id = list_key AND item_value = element_value
                );

                DROP PROCEDURE IF EXISTS titan_rt_list_temp_remove;
                CREATE PROCEDURE titan_rt_list_temp_remove(list_key VARCHAR(128), index_value INT)
                DELETE FROM titan_rt_list_tmp
                WHERE list_id = list_key AND item_index = index_value;

                DROP PROCEDURE IF EXISTS titan_rt_list_temp_cleanup;
                CREATE PROCEDURE titan_rt_list_temp_cleanup()
                DROP TEMPORARY TABLE IF EXISTS titan_rt_list_tmp;

                DROP FUNCTION IF EXISTS titan_rt_map_put;
                CREATE FUNCTION titan_rt_map_put(map_value JSON, key_value TEXT, element_value JSON)
                RETURNS JSON
                DETERMINISTIC
                RETURN JSON_SET(COALESCE(map_value, JSON_OBJECT()), CONCAT('$.', JSON_UNQUOTE(JSON_QUOTE(key_value))), element_value);

                DROP FUNCTION IF EXISTS titan_rt_map_get;
                CREATE FUNCTION titan_rt_map_get(map_value JSON, key_value TEXT)
                RETURNS JSON
                DETERMINISTIC
                RETURN JSON_EXTRACT(COALESCE(map_value, JSON_OBJECT()), CONCAT('$.', JSON_UNQUOTE(JSON_QUOTE(key_value))));

                DROP FUNCTION IF EXISTS titan_rt_map_contains_key;
                CREATE FUNCTION titan_rt_map_contains_key(map_value JSON, key_value TEXT)
                RETURNS TINYINT(1)
                DETERMINISTIC
                RETURN JSON_CONTAINS_PATH(COALESCE(map_value, JSON_OBJECT()), 'one', CONCAT('$.', JSON_UNQUOTE(JSON_QUOTE(key_value))));

                DROP FUNCTION IF EXISTS titan_rt_map_remove;
                CREATE FUNCTION titan_rt_map_remove(map_value JSON, key_value TEXT)
                RETURNS JSON
                DETERMINISTIC
                RETURN JSON_REMOVE(COALESCE(map_value, JSON_OBJECT()), CONCAT('$.', JSON_UNQUOTE(JSON_QUOTE(key_value))));

                DROP FUNCTION IF EXISTS titan_rt_map_keys;
                CREATE FUNCTION titan_rt_map_keys(map_value JSON)
                RETURNS JSON
                DETERMINISTIC
                RETURN JSON_KEYS(COALESCE(map_value, JSON_OBJECT()));

                DROP FUNCTION IF EXISTS titan_rt_map_size;
                CREATE FUNCTION titan_rt_map_size(map_value JSON)
                RETURNS INT
                DETERMINISTIC
                RETURN JSON_LENGTH(COALESCE(map_value, JSON_OBJECT()));

                DROP FUNCTION IF EXISTS titan_rt_set_add;
                CREATE FUNCTION titan_rt_set_add(set_value JSON, element_value TEXT)
                RETURNS JSON
                DETERMINISTIC
                RETURN CASE
                  WHEN JSON_CONTAINS(COALESCE(set_value, JSON_ARRAY()), JSON_QUOTE(element_value), '$')
                    THEN COALESCE(set_value, JSON_ARRAY())
                  ELSE JSON_ARRAY_APPEND(COALESCE(set_value, JSON_ARRAY()), '$', element_value)
                END;

                DROP FUNCTION IF EXISTS titan_rt_set_contains;
                CREATE FUNCTION titan_rt_set_contains(set_value JSON, element_value TEXT)
                RETURNS TINYINT(1)
                DETERMINISTIC
                RETURN JSON_CONTAINS(COALESCE(set_value, JSON_ARRAY()), JSON_QUOTE(element_value), '$');

                DROP FUNCTION IF EXISTS titan_rt_set_remove;
                CREATE FUNCTION titan_rt_set_remove(set_value JSON, element_value TEXT)
                RETURNS JSON
                DETERMINISTIC
                RETURN JSON_REMOVE(
                  COALESCE(set_value, JSON_ARRAY()),
                  COALESCE(JSON_UNQUOTE(JSON_SEARCH(COALESCE(set_value, JSON_ARRAY()), 'one', element_value)), '$[999999]')
                );

                DROP FUNCTION IF EXISTS titan_rt_set_size;
                CREATE FUNCTION titan_rt_set_size(set_value JSON)
                RETURNS INT
                DETERMINISTIC
                RETURN JSON_LENGTH(COALESCE(set_value, JSON_ARRAY()));

                DROP PROCEDURE IF EXISTS titan_rt_set_temp_init;
                CREATE PROCEDURE titan_rt_set_temp_init()
                CREATE TEMPORARY TABLE IF NOT EXISTS titan_rt_set_tmp (
                  set_id VARCHAR(128) NOT NULL,
                  item_value TEXT NOT NULL,
                  item_hash CHAR(64) NOT NULL,
                  UNIQUE KEY uq_titan_rt_set_tmp_value (set_id, item_hash)
                );

                DROP PROCEDURE IF EXISTS titan_rt_set_temp_add;
                CREATE PROCEDURE titan_rt_set_temp_add(set_key VARCHAR(128), element_value TEXT)
                INSERT IGNORE INTO titan_rt_set_tmp(set_id, item_value, item_hash)
                VALUES (set_key, element_value, SHA2(element_value, 256));

                DROP FUNCTION IF EXISTS titan_rt_set_temp_contains;
                CREATE FUNCTION titan_rt_set_temp_contains(set_key VARCHAR(128), element_value TEXT)
                RETURNS TINYINT(1)
                READS SQL DATA
                RETURN EXISTS(
                  SELECT 1
                  FROM titan_rt_set_tmp
                  WHERE set_id = set_key
                    AND item_hash = SHA2(element_value, 256)
                    AND item_value = element_value
                );

                DROP PROCEDURE IF EXISTS titan_rt_set_temp_remove;
                CREATE PROCEDURE titan_rt_set_temp_remove(set_key VARCHAR(128), element_value TEXT)
                DELETE FROM titan_rt_set_tmp
                WHERE set_id = set_key
                  AND item_hash = SHA2(element_value, 256)
                  AND item_value = element_value;

                DROP FUNCTION IF EXISTS titan_rt_set_temp_size;
                CREATE FUNCTION titan_rt_set_temp_size(set_key VARCHAR(128))
                RETURNS INT
                READS SQL DATA
                RETURN (
                  SELECT COUNT(*)
                  FROM titan_rt_set_tmp
                  WHERE set_id = set_key
                );

                DROP PROCEDURE IF EXISTS titan_rt_set_temp_cleanup;
                CREATE PROCEDURE titan_rt_set_temp_cleanup()
                DROP TEMPORARY TABLE IF EXISTS titan_rt_set_tmp;

                DROP FUNCTION IF EXISTS titan_rt_static_get;
                CREATE FUNCTION titan_rt_static_get(var_name TEXT)
                RETURNS TEXT
                DETERMINISTIC
                RETURN JSON_UNQUOTE(JSON_EXTRACT(COALESCE(@titan_static_state, JSON_OBJECT()), CONCAT('$.', JSON_UNQUOTE(JSON_QUOTE(var_name)))));

                DROP FUNCTION IF EXISTS titan_rt_static_set;
                CREATE FUNCTION titan_rt_static_set(var_name TEXT, var_value TEXT)
                RETURNS TEXT
                DETERMINISTIC
                RETURN (
                  SELECT var_value
                  FROM (
                    SELECT @titan_static_state := JSON_SET(
                      COALESCE(@titan_static_state, JSON_OBJECT()),
                      CONCAT('$.', JSON_UNQUOTE(JSON_QUOTE(var_name))),
                      JSON_QUOTE(var_value)
                    )
                  ) AS _
                );

                DROP FUNCTION IF EXISTS titan_rt_static_reset;
                CREATE FUNCTION titan_rt_static_reset(var_name TEXT)
                RETURNS JSON
                DETERMINISTIC
                RETURN @titan_static_state := JSON_REMOVE(
                  COALESCE(@titan_static_state, JSON_OBJECT()),
                  CONCAT('$.', JSON_UNQUOTE(JSON_QUOTE(var_name)))
                );
                """;
    }
}
