CREATE OR REPLACE FUNCTION "test"."int_remainder"(p_dividend INTEGER, p_divisor INTEGER)
RETURNS INTEGER
LANGUAGE plpgsql
SECURITY INVOKER
AS $$
BEGIN
    -- titan:source:build/golden-fixtures/emitter-deployability.java:242
    IF p_dividend IS NULL THEN RAISE EXCEPTION 'NullPointerException at build/golden-fixtures/emitter-deployability.java:242'; END IF;
    -- titan:source:build/golden-fixtures/emitter-deployability.java:242
    IF p_divisor IS NULL THEN RAISE EXCEPTION 'NullPointerException at build/golden-fixtures/emitter-deployability.java:242'; END IF;
    RETURN (p_dividend % p_divisor);
END;
$$;
