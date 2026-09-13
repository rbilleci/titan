CREATE OR REPLACE FUNCTION "test"."doubled"(p_value INTEGER)
RETURNS INTEGER
LANGUAGE plpgsql
SECURITY INVOKER
AS $$
BEGIN
    -- titan:source:build/golden-fixtures/time-zone.java:8
    IF p_value IS NULL THEN RAISE EXCEPTION 'NullPointerException at build/golden-fixtures/time-zone.java:8'; END IF;
    RETURN (p_value * 2);
END;
$$;
