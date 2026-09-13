CREATE OR REPLACE FUNCTION "test"."wrap_sub"(p_a INTEGER, p_b INTEGER)
RETURNS INTEGER
LANGUAGE plpgsql
SECURITY INVOKER
AS $$
BEGIN
    -- titan:source:build/golden-fixtures/strict-wraparound.java:9
    IF p_a IS NULL THEN RAISE EXCEPTION 'NullPointerException at build/golden-fixtures/strict-wraparound.java:9'; END IF;
    -- titan:source:build/golden-fixtures/strict-wraparound.java:9
    IF p_b IS NULL THEN RAISE EXCEPTION 'NullPointerException at build/golden-fixtures/strict-wraparound.java:9'; END IF;
    RETURN titan_runtime.java_int_sub(p_a, p_b);
END;
$$;
