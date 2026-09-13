package io.titan.transpiler.tir;

/**
 * PostgreSQL runtime helper deployment.
 *
 * <p>Arithmetic emulation helpers (plan 2.4, audit E-7/E-11):
 * <ul>
 *   <li>{@code java_int_add/sub/mul}: 32-bit Java wraparound via 64-bit arithmetic and the
 *       {@code ((x + 2^31) mod 2^32) - 2^31} double-mod reduction; wired by
 *       {@link EmulationInsertionPass} (strict-wraparound mode) through
 *       {@link PostgreSqlEmitter}'s {@code __titan_int_add/sub/mul} markers. Correctness
 *       (extremes and negative operands) is pinned by {@code RuntimeArithmeticEmulationIT}.</li>
 *   <li>{@code java_mod}: deliberately <em>unwired</em>. Native PostgreSQL {@code %} already
 *       matches Java exactly for integral operands (dividend-sign remainder, SQLSTATE 22012 on
 *       zero), and the TRUNC-based formula here is provably identical to it — it came from the
 *       design document's generic emulation list, not from an actual PostgreSQL divergence. The
 *       MySQL twin <em>is</em> wired, because MySQL's mod-by-zero silently yields NULL. It stays
 *       deployed for backward compatibility of existing runtime installations.</li>
 * </ul>
 */
final class PostgreSqlRuntimeStrategy implements RuntimeStrategy {
    @Override
    public String runtimeNamespace() {
        return "titan_runtime";
    }

    @Override
    public boolean supportsNativeArrays() {
        return true;
    }

    @Override
    public java.util.List<SqlObject> runtimeObjects() {
        String ns = runtimeNamespace();
        return java.util.List.of(
                SqlObject.of(SqlObject.Kind.TABLE, ns, "telemetry"),
                fn(ns, "java_mod", "BIGINT", p("a", "BIGINT"), p("b", "BIGINT")),
                fn(ns, "java_round", "NUMERIC", p("v", "NUMERIC"), p("scale", "INT")),
                fn(ns, "java_int_add", "INTEGER", p("a", "INTEGER"), p("b", "INTEGER")),
                fn(ns, "java_int_sub", "INTEGER", p("a", "INTEGER"), p("b", "INTEGER")),
                fn(ns, "java_int_mul", "INTEGER", p("a", "INTEGER"), p("b", "INTEGER")),
                fn(ns, "list_add", "TEXT[]", p("list_value", "TEXT[]"), p("element_value", "TEXT")),
                fn(ns, "list_get", "TEXT", p("list_value", "TEXT[]"), p("index_value", "INTEGER")),
                fn(ns, "list_size", "INTEGER", p("list_value", "TEXT[]")),
                fn(ns, "list_remove", "TEXT[]", p("list_value", "TEXT[]"), p("index_value", "INTEGER")),
                fn(ns, "list_contains", "BOOLEAN", p("list_value", "TEXT[]"), p("element_value", "TEXT")),
                fn(ns, "map_put", "JSONB", p("map_value", "JSONB"), p("key_value", "TEXT"), p("element_value", "JSONB")),
                fn(ns, "map_get", "JSONB", p("map_value", "JSONB"), p("key_value", "TEXT")),
                fn(ns, "map_contains_key", "BOOLEAN", p("map_value", "JSONB"), p("key_value", "TEXT")),
                fn(ns, "map_remove", "JSONB", p("map_value", "JSONB"), p("key_value", "TEXT")),
                fn(ns, "map_keys", "TEXT[]", p("map_value", "JSONB")),
                fn(ns, "map_size", "INTEGER", p("map_value", "JSONB")),
                fn(ns, "set_add", "TEXT[]", p("set_value", "TEXT[]"), p("element_value", "TEXT")),
                fn(ns, "set_contains", "BOOLEAN", p("set_value", "TEXT[]"), p("element_value", "TEXT")),
                fn(ns, "set_remove", "TEXT[]", p("set_value", "TEXT[]"), p("element_value", "TEXT")),
                fn(ns, "set_size", "INTEGER", p("set_value", "TEXT[]")),
                fn(ns, "static_get", "TEXT", p("var_name", "TEXT")),
                fn(ns, "static_set", "TEXT", p("var_name", "TEXT"), p("var_value", "TEXT")),
                fn(ns, "static_reset", "VOID", p("var_name", "TEXT")));
    }

    private static SqlObject fn(String schema, String name, String returnType, SqlObject.Parameter... parameters) {
        return new SqlObject(SqlObject.Kind.FUNCTION, schema, name, java.util.List.of(parameters), returnType, "");
    }

    private static SqlObject.Parameter p(String name, String type) {
        return new SqlObject.Parameter(name, type);
    }

    @Override
    public String runtimeMigrationSql() {
        return """
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
    }
}
