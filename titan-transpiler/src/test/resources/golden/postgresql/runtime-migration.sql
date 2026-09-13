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
