CREATE OR REPLACE FUNCTION "test"."reject_negative"(p_value INTEGER)
RETURNS INTEGER
LANGUAGE plpgsql
SECURITY INVOKER
AS $$
BEGIN
    IF COALESCE((p_value < 0), FALSE) THEN
        RAISE EXCEPTION USING ERRCODE = '45000', MESSAGE = 'TITAN_GATE_NEGATIVE';
    END IF;
    -- titan:source:build/golden-fixtures/emitter-deployability.java:30
    IF p_value IS NULL THEN RAISE EXCEPTION 'NullPointerException at build/golden-fixtures/emitter-deployability.java:30'; END IF;
    RETURN (p_value * 2);
END;
$$;
