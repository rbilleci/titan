CREATE OR REPLACE FUNCTION "test"."cast_chain"(p_small INTEGER, p_ratio NUMERIC(38,10))
RETURNS BIGINT
LANGUAGE plpgsql
SECURITY INVOKER
AS $$
DECLARE
    v_widened BIGINT;
    v_scaled NUMERIC(38,10);
    v_truncated BIGINT;
    v_identity INTEGER;
BEGIN
    v_widened := CAST(p_small AS BIGINT);
    -- titan:source:build/golden-fixtures/emitter-deployability.java:157
    IF p_ratio IS NULL THEN RAISE EXCEPTION 'NullPointerException at build/golden-fixtures/emitter-deployability.java:157'; END IF;
    v_scaled := (CAST(v_widened AS NUMERIC(38,10)) * p_ratio);
    v_truncated := CAST(TRUNC(v_scaled) AS BIGINT);
    v_identity := p_small;
    -- titan:source:build/golden-fixtures/emitter-deployability.java:157
    IF v_widened IS NULL THEN RAISE EXCEPTION 'NullPointerException at build/golden-fixtures/emitter-deployability.java:157'; END IF;
    -- titan:source:build/golden-fixtures/emitter-deployability.java:157
    IF v_truncated IS NULL THEN RAISE EXCEPTION 'NullPointerException at build/golden-fixtures/emitter-deployability.java:157'; END IF;
    RETURN ((v_widened + v_truncated) + v_identity);
END;
$$;
