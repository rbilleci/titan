package io.titan.runtime.testing;

final class RuntimeMigrations {
    private RuntimeMigrations() {
    }

    static String forTarget(DatabaseTarget target) {
        return switch (target) {
            case POSTGRESQL -> POSTGRESQL_SQL;
            case MYSQL -> MYSQL_SQL;
        };
    }

    private static final String POSTGRESQL_SQL = """
            CREATE SCHEMA IF NOT EXISTS titan_runtime;

            CREATE TABLE IF NOT EXISTS titan_runtime.telemetry (
              id BIGSERIAL PRIMARY KEY,
              procedure_name TEXT NOT NULL,
              started_at TIMESTAMPTZ NOT NULL,
              finished_at TIMESTAMPTZ,
              duration_ms DOUBLE PRECISION,
              rows_affected BIGINT,
              status TEXT NOT NULL DEFAULT 'running',
              error_sqlstate CHAR(5),
              error_message TEXT,
              parameters JSONB
            );

            CREATE OR REPLACE FUNCTION titan_runtime.java_mod(a BIGINT, b BIGINT)
            RETURNS BIGINT
            LANGUAGE SQL
            IMMUTABLE
            AS $$
              SELECT a - (TRUNC(a::NUMERIC / b::NUMERIC)::BIGINT * b);
            $$;

            CREATE OR REPLACE FUNCTION titan_runtime.java_round(v NUMERIC, scale INT)
            RETURNS NUMERIC
            LANGUAGE SQL
            IMMUTABLE
            AS $$
              SELECT CASE
                WHEN abs(v * power(10::NUMERIC, scale) - trunc(v * power(10::NUMERIC, scale))) = 0.5
                     AND mod(abs(trunc(v * power(10::NUMERIC, scale))), 2) = 0
                  THEN trunc(v * power(10::NUMERIC, scale)) / power(10::NUMERIC, scale)
                ELSE round(v, scale)
              END;
            $$;

            CREATE OR REPLACE FUNCTION titan_runtime.java_int_add(a INTEGER, b INTEGER)
            RETURNS INTEGER
            LANGUAGE SQL
            IMMUTABLE
            AS $$
              SELECT (((((a::BIGINT + b::BIGINT + 2147483648) % 4294967296) + 4294967296) % 4294967296) - 2147483648)::INTEGER;
            $$;

            CREATE OR REPLACE FUNCTION titan_runtime.java_int_sub(a INTEGER, b INTEGER)
            RETURNS INTEGER
            LANGUAGE SQL
            IMMUTABLE
            AS $$
              SELECT (((((a::BIGINT - b::BIGINT + 2147483648) % 4294967296) + 4294967296) % 4294967296) - 2147483648)::INTEGER;
            $$;

            CREATE OR REPLACE FUNCTION titan_runtime.java_int_mul(a INTEGER, b INTEGER)
            RETURNS INTEGER
            LANGUAGE SQL
            IMMUTABLE
            AS $$
              SELECT (((((a::BIGINT * b::BIGINT + 2147483648) % 4294967296) + 4294967296) % 4294967296) - 2147483648)::INTEGER;
            $$;

            CREATE OR REPLACE FUNCTION titan_runtime.list_add(list_value TEXT[], element_value TEXT)
            RETURNS TEXT[]
            LANGUAGE SQL
            IMMUTABLE
            AS $$
              SELECT array_append(COALESCE(list_value, ARRAY[]::TEXT[]), element_value);
            $$;

            CREATE OR REPLACE FUNCTION titan_runtime.list_get(list_value TEXT[], index_value INTEGER)
            RETURNS TEXT
            LANGUAGE SQL
            IMMUTABLE
            AS $$
              SELECT (COALESCE(list_value, ARRAY[]::TEXT[]))[index_value + 1];
            $$;

            CREATE OR REPLACE FUNCTION titan_runtime.list_size(list_value TEXT[])
            RETURNS INTEGER
            LANGUAGE SQL
            IMMUTABLE
            AS $$
              SELECT COALESCE(array_length(list_value, 1), 0);
            $$;

            CREATE OR REPLACE FUNCTION titan_runtime.list_remove(list_value TEXT[], index_value INTEGER)
            RETURNS TEXT[]
            LANGUAGE SQL
            IMMUTABLE
            AS $$
              SELECT CASE
                WHEN index_value < 0 OR index_value >= COALESCE(array_length(list_value, 1), 0)
                  THEN COALESCE(list_value, ARRAY[]::TEXT[])
                ELSE (COALESCE(list_value, ARRAY[]::TEXT[]))[1:index_value]
                     || (COALESCE(list_value, ARRAY[]::TEXT[]))[index_value + 2:COALESCE(array_length(list_value, 1), 0)]
              END;
            $$;

            CREATE OR REPLACE FUNCTION titan_runtime.list_contains(list_value TEXT[], element_value TEXT)
            RETURNS BOOLEAN
            LANGUAGE SQL
            IMMUTABLE
            AS $$
              SELECT element_value = ANY(COALESCE(list_value, ARRAY[]::TEXT[]));
            $$;

            CREATE OR REPLACE FUNCTION titan_runtime.map_put(map_value JSONB, key_value TEXT, element_value JSONB)
            RETURNS JSONB
            LANGUAGE SQL
            IMMUTABLE
            AS $$
              SELECT jsonb_set(COALESCE(map_value, '{}'::JSONB), ARRAY[key_value], element_value, true);
            $$;

            CREATE OR REPLACE FUNCTION titan_runtime.map_get(map_value JSONB, key_value TEXT)
            RETURNS JSONB
            LANGUAGE SQL
            IMMUTABLE
            AS $$
              SELECT COALESCE(map_value, '{}'::JSONB) -> key_value;
            $$;

            CREATE OR REPLACE FUNCTION titan_runtime.map_contains_key(map_value JSONB, key_value TEXT)
            RETURNS BOOLEAN
            LANGUAGE SQL
            IMMUTABLE
            AS $$
              SELECT COALESCE(map_value, '{}'::JSONB) ? key_value;
            $$;

            CREATE OR REPLACE FUNCTION titan_runtime.map_remove(map_value JSONB, key_value TEXT)
            RETURNS JSONB
            LANGUAGE SQL
            IMMUTABLE
            AS $$
              SELECT COALESCE(map_value, '{}'::JSONB) - key_value;
            $$;

            CREATE OR REPLACE FUNCTION titan_runtime.map_keys(map_value JSONB)
            RETURNS TEXT[]
            LANGUAGE SQL
            IMMUTABLE
            AS $$
              SELECT COALESCE(ARRAY(SELECT jsonb_object_keys(COALESCE(map_value, '{}'::JSONB))), ARRAY[]::TEXT[]);
            $$;

            CREATE OR REPLACE FUNCTION titan_runtime.map_size(map_value JSONB)
            RETURNS INTEGER
            LANGUAGE SQL
            IMMUTABLE
            AS $$
              SELECT COALESCE((SELECT COUNT(*) FROM jsonb_object_keys(COALESCE(map_value, '{}'::JSONB))), 0)::INTEGER;
            $$;

            CREATE OR REPLACE FUNCTION titan_runtime.set_add(set_value TEXT[], element_value TEXT)
            RETURNS TEXT[]
            LANGUAGE SQL
            IMMUTABLE
            AS $$
              SELECT CASE
                WHEN element_value = ANY(COALESCE(set_value, ARRAY[]::TEXT[]))
                  THEN COALESCE(set_value, ARRAY[]::TEXT[])
                ELSE array_append(COALESCE(set_value, ARRAY[]::TEXT[]), element_value)
              END;
            $$;

            CREATE OR REPLACE FUNCTION titan_runtime.set_contains(set_value TEXT[], element_value TEXT)
            RETURNS BOOLEAN
            LANGUAGE SQL
            IMMUTABLE
            AS $$
              SELECT element_value = ANY(COALESCE(set_value, ARRAY[]::TEXT[]));
            $$;

            CREATE OR REPLACE FUNCTION titan_runtime.set_remove(set_value TEXT[], element_value TEXT)
            RETURNS TEXT[]
            LANGUAGE SQL
            IMMUTABLE
            AS $$
              SELECT COALESCE(ARRAY(
                SELECT existing
                FROM unnest(COALESCE(set_value, ARRAY[]::TEXT[])) AS existing
                WHERE existing IS DISTINCT FROM element_value
              ), ARRAY[]::TEXT[]);
            $$;

            CREATE OR REPLACE FUNCTION titan_runtime.set_size(set_value TEXT[])
            RETURNS INTEGER
            LANGUAGE SQL
            IMMUTABLE
            AS $$
              SELECT COALESCE(array_length(COALESCE(set_value, ARRAY[]::TEXT[]), 1), 0);
            $$;

            CREATE OR REPLACE FUNCTION titan_runtime.static_get(var_name TEXT)
            RETURNS TEXT
            LANGUAGE SQL
            STABLE
            AS $$
              SELECT current_setting('titan.static.' || var_name, true);
            $$;

            CREATE OR REPLACE FUNCTION titan_runtime.static_set(var_name TEXT, var_value TEXT)
            RETURNS TEXT
            LANGUAGE SQL
            VOLATILE
            AS $$
              SELECT set_config('titan.static.' || var_name, COALESCE(var_value, ''), true);
            $$;

            CREATE OR REPLACE FUNCTION titan_runtime.static_reset(var_name TEXT)
            RETURNS VOID
            LANGUAGE SQL
            VOLATILE
            AS $$
              SELECT set_config('titan.static.' || var_name, '', true);
            $$;
            """;

    private static final String MYSQL_SQL = """
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
